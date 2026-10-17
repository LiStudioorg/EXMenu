package cn.handyplus.menu.ng;

import cn.handyplus.menu.ng.command.MainCommand;
import cn.handyplus.menu.ng.config.MenuConfig;
import cn.handyplus.menu.ng.core.MenuExecutor;
import cn.handyplus.menu.ng.inventory.ConfigProvider;
import cn.handyplus.menu.ng.inventory.InputManager;
import cn.handyplus.menu.ng.inventory.MenuGuiManager;
import cn.handyplus.menu.ng.listener.ChatInputListener;
import cn.handyplus.menu.ng.listener.ClockInteractListener;
import cn.handyplus.menu.ng.listener.PlayerQuitListener;
import cn.handyplus.menu.ng.service.MenuItemService;
import cn.handyplus.menu.ng.service.MenuLimitService;
import cn.handyplus.menu.ng.storage.MysqlStorage;
import cn.handyplus.menu.ng.storage.SqliteStorage;
import cn.handyplus.menu.ng.storage.Storage;
import cn.handyplus.menu.ng.util.EconomyUtil;
import cn.handyplus.menu.ng.util.ItemStackUtil;
import cn.handyplus.menu.ng.util.PlaceholderUtil;
import cn.handyplus.menu.ng.util.SchedulerUtil;
import cn.handyplus.menu.ng.util.YamlUtil;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.logging.Level;

/**
 * 插件主类。
 *
 * <p>本类只做三件事：<b>构造组件、接线、注册</b>。所有业务逻辑都在各自的类里，
 * 这里不出现任何判断菜单内容或操作数据的代码 —— 主类一旦开始写业务，
 * 就会变成什么都往里塞的杂物间。
 *
 * <p>启动顺序有硬性依赖，不能随意调整：
 * <ol>
 *   <li><b>配置</b> —— 后面所有组件都要读它</li>
 *   <li><b>存储</b> —— 建表失败必须直接禁用插件，带着坏掉的数据库继续跑
 *       只会在玩家点按钮时抛一堆异常</li>
 *   <li><b>工具初始化</b> —— 材质兼容层与占位符钩子</li>
 *   <li><b>业务组件</b> —— DAO、执行器、界面管理器</li>
 *   <li><b>接线</b> —— 双向回调（执行器 ↔ 界面管理器）必须都接上，
 *       漏一个的表现是"点了按钮没反应"，且不报错，极难排查</li>
 *   <li><b>注册</b> —— 命令与监听器</li>
 * </ol>
 *
 * <p>本类同时实现 {@link ConfigProvider}：配置快照由主类持有，
 * {@code /plm reload} 时就地刷新这个实例，因此各处持有的引用始终有效。
 */
public final class MenuPlugin extends JavaPlugin implements ConfigProvider {

    /** 当前配置快照，reload 时就地更新。 */
    private volatile MenuConfig config;

    /** 存储后端。 */
    private Storage storage;

    /** 界面管理器。 */
    private MenuGuiManager guiManager;

    /** 动作执行器。 */
    private MenuExecutor executor;

    /** 限制 DAO，主类持有以便子动作与命令共用同一实例。 */
    private MenuLimitService limitService;

    /** 物品库 DAO。 */
    private MenuItemService itemService;

    @Override
    public void onEnable() {
        // 1. 配置
        try {
            this.config = MenuConfig.load(this);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "加载配置失败，插件将被禁用", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 2. 存储
        if (!initStorage()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 3. 工具
        ItemStackUtil.init();
        EconomyUtil.init();
        PlaceholderUtil.init();

        // 4. 业务组件
        this.itemService = new MenuItemService(storage, getLogger());
        this.limitService = new MenuLimitService(storage, getLogger());

        this.executor = new MenuExecutor(this, limitService, config);
        // 界面管理器同时是 ConfigProvider —— 它内部转发给主类持有的快照，
        // 这样 reload 替换快照后界面层立刻取到新值
        this.guiManager = new MenuGuiManager(this, this, itemService, limitService, executor);

        // 5. 接线：执行器需要界面层来打开菜单与执行子动作
        executor.setGuiOpener(guiManager);
        // 子动作（[action] 菜单名:按钮id）需要"以当前玩家再执行一个按钮"。
        // 界面层没有实现 SubActionHandler，这里由主类补上：取出目标按钮，
        // 走与普通点击完全相同的执行路径（含限制累加与冷却写入）。
        executor.setSubActionHandler(new MenuExecutor.SubActionHandler() {
            @Override
            public void run(org.bukkit.entity.Player player, String menu, int id, MenuExecutor.GuiContext gui) {
                executeSubAction(player, menu, id, gui);
            }
        });

        InputManager.PluginHolder.set(this);
        InputManager.ConfigHolder.set(this);
        InputManager.GuiHolder.set(guiManager);

        // 6. 注册
        registerCommand();
        registerListeners();

        getLogger().info("PlayerMenuNG 已启用，存储: " + storage.dialect());
    }

    /**
     * 执行一个子按钮（{@code [action] 菜单名:按钮id}）。
     *
     * <p>注意 {@code id} 在这里是<b>按钮 id</b>而非按钮下标 —— 与点击事件一致。
     * 找不到对应按钮时不做任何事，避免把"配置写错"变成玩家侧的报错。
     *
     * @param player 玩家
     * @param menu   菜单名（可带或不带 .yml）
     * @param id     按钮 id
     * @param gui    来源界面
     */
    private void executeSubAction(org.bukkit.entity.Player player, String menu, int id,
                                  MenuExecutor.GuiContext gui) {
        cn.handyplus.menu.ng.param.MenuButtonParam param = guiManager.findButtonById(menu, id);
        if (param == null) {
            return;
        }
        // 读限制记录要碰数据库，必须异步；拿到后再回玩家线程执行动作链
        SchedulerUtil.runTaskAsynchronously(this, () -> {
            java.util.Map<Integer, cn.handyplus.menu.ng.enter.MenuLimit> limits =
                    limitService.findMapByPlayerUuid(player.getUniqueId());
            cn.handyplus.menu.ng.enter.MenuLimit limit =
                    param.getId() == 0 ? null : limits.get(param.getId());
            SchedulerUtil.runAtEntity(MenuPlugin.this, player,
                    () -> executor.execute(player, param, limit, gui));
        });
    }

    @Override
    public void onDisable() {
        // 先取消所有挂起的任务，再关数据库。
        // 顺序不能反：延迟动作（[delay] 续跑、输入超时）如果在连接池关闭后
        // 才触发，会拿不到连接而抛异常；更糟的是插件已卸载却仍在改玩家背包。
        try {
            cn.handyplus.menu.ng.util.SchedulerUtil.cancelTasks(this);
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "取消调度任务失败", e);
        }
        if (storage != null) {
            try {
                storage.close();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "关闭存储失败", e);
            }
        }
        getLogger().info("PlayerMenuNG 已卸载");
    }

    /**
     * 初始化存储后端。
     *
     * <p>{@code storage.yml} 不存在时从 jar 释放一份，与其它配置文件的处理方式一致。
     *
     * @return true 表示初始化成功
     */
    private boolean initStorage() {
        File file = new File(getDataFolder(), "storage.yml");
        if (!file.exists()) {
            saveResource("storage.yml", false);
        }
        YamlConfiguration storageConfig = YamlUtil.load(file);

        String type = storageConfig.getString("type", "sqlite").toLowerCase();
        this.storage = "mysql".equals(type) ? new MysqlStorage() : new SqliteStorage();

        try {
            storage.init(this, storageConfig);
            return true;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "初始化数据库失败(" + type + ")，插件将被禁用", e);
            return false;
        }
    }

    /**
     * 注册主命令与别名。
     *
     * <p>{@code plugin.yml} 里声明的别名（{@code plm}）由服务端自动处理，
     * 这里只需给主命令挂上执行器。
     */
    private void registerCommand() {
        PluginCommand command = getCommand("playerMenu");
        if (command == null) {
            // plugin.yml 写错时 getCommand 返回 null，直接抛异常会让整个 onEnable 失败，
            // 这里只告警：插件其它功能仍可用，管理员能登录服务器排查
            getLogger().severe("plugin.yml 中未声明 playerMenu 命令，命令功能不可用");
            return;
        }
        MainCommand handler = new MainCommand(this, this, guiManager, executor,
                itemService, limitService);
        command.setExecutor(handler);
        command.setTabCompleter(handler);
    }

    /**
     * 注册事件监听器。
     */
    private void registerListeners() {
        getServer().getPluginManager().registerEvents(
                new ClockInteractListener(this, this, executor), this);

        getServer().getPluginManager().registerEvents(new PlayerQuitListener(), this);

        // 聊天输入：把静态的 InputManager 适配成监听器要求的处理器接口
        getServer().getPluginManager().registerEvents(new ChatInputListener(new ChatInputListener.ChatHandler() {
            @Override
            public boolean hasInput(org.bukkit.entity.Player player) {
                return InputManager.isWaiting(player);
            }

            @Override
            public void handle(org.bukkit.entity.Player player, String message) {
                InputManager.handle(MenuPlugin.this, player, message, executor);
            }
        }), this);
    }

    /**
     * 取当前配置快照。
     *
     * @return 配置，永不返回 null
     */
    @Override
    public MenuConfig get() {
        return config;
    }
}
