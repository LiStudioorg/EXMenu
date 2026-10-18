package cn.listudio.exmenu.inventory;

import cn.listudio.exmenu.config.MenuConfig;
import cn.listudio.exmenu.constants.InputTypeEnum;
import cn.listudio.exmenu.core.MenuChecker;
import cn.listudio.exmenu.core.MenuExecutor;
import cn.listudio.exmenu.enter.MenuLimit;
import cn.listudio.exmenu.param.MenuButtonParam;
import cn.listudio.exmenu.service.MenuItemService;
import cn.listudio.exmenu.service.MenuLimitService;
import cn.listudio.exmenu.util.ItemStackUtil;
import cn.listudio.exmenu.util.LangUtil;
import cn.listudio.exmenu.util.PlaceholderUtil;
import cn.listudio.exmenu.util.SchedulerUtil;
import cn.listudio.exmenu.util.TextUtil;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.Pane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import com.github.stefvanschie.inventoryframework.pane.util.Slot;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 菜单界面的构建与渲染。
 *
 * <p>渲染流程分四步：
 * <ol>
 *   <li><b>过滤权限</b> —— 玩家没有权限的按钮直接不参与渲染</li>
 *   <li><b>批量取数据</b> —— 一次性把用到的物品库条目与限制记录捞回来</li>
 *   <li><b>逐按钮求值</b> —— 替换变量、判断隐藏条件、处理同槽位冲突</li>
 *   <li><b>装配界面</b> —— 交给 IFramework 显示</li>
 * </ol>
 *
 * <p>第 2 步是性能关键。一个菜单可能有 54 个按钮，逐个查库就是 54 次往返；
 * 这里用两次 {@code IN} 查询解决。
 */
public final class MenuGuiManager implements MenuExecutor.GuiOpener, ConfigProvider {

    /** 插件实例。 */
    private final Plugin plugin;

    /** 配置快照提供者。 */
    private final ConfigProvider configProvider;

    /** 物品库 DAO。 */
    private final MenuItemService itemService;

    /** 限制 DAO。 */
    private final MenuLimitService limitService;

    /** 执行器，渲染时注入到点击回调里。 */
    private final MenuExecutor executor;

    /** 限制缓存（可选注入）：原子占位写库后失效，保证 PAPI 状态变量即时回显。 */
    private cn.listudio.exmenu.core.LimitCache limitCache;

    /** 按钮缓存：菜单文件名 → 按钮列表。配置不改就一直是同一份对象。 */
    private final Map<String, List<MenuButtonParam>> buttonCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 构造界面管理器。
     *
     * @param plugin         插件实例
     * @param configProvider 配置快照提供者
     * @param itemService    物品库 DAO
     * @param limitService   限制 DAO
     * @param executor       执行器
     */
    public MenuGuiManager(Plugin plugin, ConfigProvider configProvider,
                          MenuItemService itemService, MenuLimitService limitService,
                          MenuExecutor executor) {
        this.plugin = plugin;
        this.configProvider = configProvider;
        this.itemService = itemService;
        this.limitService = limitService;
        this.executor = executor;
    }

    /** 配置快照提供者，reload 后取到的是新对象。 */
    @Override
    public MenuConfig get() {
        return configProvider.get();
    }

    /**
     * 清空按钮缓存。
     *
     * <p>reload 时调用 —— 缓存的是解析后的对象，配置变了就必须丢弃，
     * 否则玩家会看到旧菜单直到重启。
     */
    public void invalidateCache() {
        buttonCache.clear();
    }

    /**
     * 注入限制缓存。
     *
     * @param limitCache 限制缓存
     */
    public void setLimitCache(cn.listudio.exmenu.core.LimitCache limitCache) {
        this.limitCache = limitCache;
    }

    /**
     * 丢弃单个菜单的按钮缓存。
     *
     * <p>可视化编辑器每改一个按钮就保存一次，只需让被改的菜单失效，
     * 全量清空会让其他菜单在下次打开时白白重新解析。
     *
     * @param menuFile 菜单文件名（含 .yml）
     */
    public void invalidateCache(String menuFile) {
        buttonCache.remove(menuFile);
    }

    /**
     * 解析一个菜单的全部按钮，带缓存。
     *
     * @param menuFile 菜单文件名（含 .yml）
     * @param menu     菜单配置
     * @return 按钮列表
     */
    private List<MenuButtonParam> parseButtons(String menuFile, YamlConfiguration menu) {
        List<MenuButtonParam> cached = buttonCache.get(menuFile);
        if (cached != null) {
            return cached;
        }

        List<MenuButtonParam> buttons = new ArrayList<>();
        List<Map<?, ?>> rawList = menu.getMapList("buttons");
        for (int i = 0; i < rawList.size(); i++) {
            Map<?, ?> raw = rawList.get(i);
            ConfigurationSection section = toSection(menu, "buttons", i, raw);
            if (section == null) {
                continue;
            }
            MenuButtonParam param = ButtonParser.parse(section, menuFile, i);
            if (param != null) {
                buttons.add(param);
            }
        }

        buttonCache.put(menuFile, buttons);
        return buttons;
    }

    /**
     * 把 {@code MapList} 里的一项包装成 {@code ConfigurationSection}。
     *
     * <p>Bukkit 的 {@code getMapList} 返回的是纯 Map，没有路径信息，
     * 而 {@code ButtonParser} 需要按路径取值。这里借一个临时配置对象
     * 把它挂到指定下标下再取回，避免让解析器同时接受两种类型。
     *
     * @param owner 宿主配置
     * @param path  列表路径
     * @param index 下标
     * @param raw   原始 Map
     * @return 配置节，失败时返回 null
     */
    private ConfigurationSection toSection(YamlConfiguration owner, String path, int index, Map<?, ?> raw) {
        String key = path + "." + index;
        owner.createSection(key, new LinkedHashMap<>(raw));
        return owner.getConfigurationSection(key);
    }

    /**
     * 打开一个菜单。
     *
     * <p><b>线程编排</b>：检查与按钮解析（纯内存）在当前线程完成；两次批量查库
     * 移到异步线程；界面构建与显示回到玩家所在线程 —— 这既满足"数据库操作
     * 不得阻塞游戏线程"的硬规则，也满足"IFramework 非线程安全、必须在实体线程
     * 构建"的要求。返回值表示<b>检查是否通过</b>，界面在之后一拍的实体任务里出现。
     *
     * @param player   玩家
     * @param menuName 菜单文件名
     * @return true 表示检查通过、界面已排入渲染队列
     */
    @Override
    public boolean open(Player player, String menuName) {
        MenuConfig config = configProvider.get();
        YamlConfiguration menu = config.getMenu(menuName);
        if (menu == null) {
            player.sendMessage(LangUtil.message("noMenu"));
            return false;
        }

        String menuFile = menuName.toLowerCase().endsWith(".yml") ? menuName : menuName + ".yml";

        // ---- 权限与世界检查 ----
        if (!checkPermission(player, menuFile, config)) {
            player.sendMessage(LangUtil.message("noPermission"));
            return false;
        }
        if (isWorldBlocked(player, config)) {
            player.sendMessage(LangUtil.message("noCommandPermission"));
            return false;
        }

        int size = normalizeSize(menu.getInt("size", 27));
        String title = TextUtil.color(PlaceholderUtil.set(player, menu.getString("title", "菜单")));

        ChestGui gui = Guis.chest(size / 9, title);
        gui.setOnGlobalClick(event -> event.setCancelled(true));

        List<MenuButtonParam> buttons = parseButtons(menuFile, menu);

        // ---- 批量取数据：两次查询移到异步线程，避免主线程阻塞 IO ----
        List<Integer> libraryIds = new ArrayList<>();
        for (MenuButtonParam param : buttons) {
            if (param.getId() > 0) {
                libraryIds.add(param.getId());
            }
        }

        // 渲染级 PAPI 缓存：同一段文本在一次渲染里通常被 material/name/lore
        // 多路引用，缓存后每个唯一字符串只解析一次
        final Map<String, String> papiCache = new HashMap<>();

        SchedulerUtil.runTaskAsynchronously(plugin, () -> {
            Map<Integer, cn.listudio.exmenu.enter.MenuItem> itemLibrary =
                    itemService.findMapByIds(libraryIds);
            Map<Integer, MenuLimit> limits = limitService.findMapByPlayerUuid(player.getUniqueId());

            SchedulerUtil.runAtEntity(plugin, player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                StaticPane pane = new StaticPane(9, size / 9);
                // 记录每个槽位当前的优先级，用于解决同槽位冲突
                Map<Integer, Integer> slotPriority = new HashMap<>();

                for (MenuButtonParam param : buttons) {
                    if (!passesPermission(player, param)) {
                        continue;
                    }
                    if (shouldHide(player, param, limits)) {
                        continue;
                    }

                    ItemStack itemStack = buildItem(player, param, itemLibrary, limits, papiCache);
                    if (itemStack == null) {
                        continue;
                    }

                    for (Integer slot : param.getSlots()) {
                        if (slot >= size) {
                            continue;
                        }
                        // 同槽位冲突：priority 高的胜出，相等则后者覆盖前者
                        Integer existing = slotPriority.get(slot);
                        if (existing != null && existing > param.getPriority()) {
                            continue;
                        }
                        slotPriority.put(slot, param.getPriority());
                        pane.addItem(createGuiItem(player, param, itemStack, gui, limits),
                                Slot.fromIndex(slot));
                    }
                }

                // 玻璃板背景：只在配置指定时铺，避免覆盖玩家自己的设计
                applyPane(pane, menu, size, slotPriority);

                gui.addPane(Slot.fromIndex(0), pane);
                gui.show(player);
            });
        });
        return true;
    }

    /**
     * 铺背景装饰板。
     *
     * <p>支持两种写法：
     * <ul>
     *   <li><b>配置节</b>（推荐，{@code example.yml} 用的就是这种）：
     *       {@code pane.enable} / {@code pane.material} / {@code pane.slots} / {@code pane.name}</li>
     *   <li><b>旧式平铺字符串</b>：{@code pane: BLACK_STAINED_GLASS_PANE}</li>
     * </ul>
     *
     * <p>历史缺陷：早先只写了 {@code menu.getString("pane", "")}，而配置里 {@code pane}
     * 是一个**配置节**，{@code getString} 对节返回 null —— 于是装饰板从未生效，
     * 文档里描述的 {@code slots} / {@code enable} 也全是摆设。
     *
     * @param pane     面板
     * @param menu     菜单配置
     * @param size     界面大小
     * @param occupied 已被按钮占用的槽位
     */
    private void applyPane(StaticPane pane, YamlConfiguration menu, int size,
                           Map<Integer, Integer> occupied) {
        // ---- 情形一：配置节 ----
        if (menu.isConfigurationSection("pane")) {
            ConfigurationSection section = menu.getConfigurationSection("pane");
            if (section == null || !section.getBoolean("enable", true)) {
                return;
            }
            String material = section.getString("material", "");
            if (TextUtil.isEmpty(material)) {
                return;
            }
            ItemStack filler = ItemStackUtil.build(material,
                    section.getString("name", " "),
                    java.util.Collections.emptyList(),
                    false, 0, true, null, null);
            if (filler == null) {
                return;
            }
            GuiItem guiItem = new GuiItem(filler, event -> event.setCancelled(true));
            // slots 为空表示"铺满所有未被占用的槽位"
            List<Integer> slots = section.getIntegerList("slots");
            if (slots.isEmpty()) {
                for (int slot = 0; slot < size; slot++) {
                    if (!occupied.containsKey(slot)) {
                        pane.addItem(guiItem, Slot.fromIndex(slot));
                    }
                }
                return;
            }
            for (Integer slot : slots) {
                if (slot == null || slot < 0 || slot >= size) {
                    continue;
                }
                if (occupied.containsKey(slot)) {
                    continue;
                }
                pane.addItem(guiItem, Slot.fromIndex(slot));
            }
            return;
        }

        // ---- 情形二：旧式平铺字符串 ----
        String paneMaterial = menu.getString("pane", "");
        if (TextUtil.isEmpty(paneMaterial)) {
            return;
        }
        ItemStack filler = ItemStackUtil.parse(paneMaterial);
        if (filler == null) {
            return;
        }
        GuiItem guiItem = new GuiItem(filler, event -> event.setCancelled(true));
        for (int slot = 0; slot < size; slot++) {
            if (!occupied.containsKey(slot)) {
                pane.addItem(guiItem, Slot.fromIndex(slot));
            }
        }
    }

    /**
     * 创建点击回调。
     *
     * <p>这是整个插件最热的代码路径，所以回调里只做必要的事：
     * 绑定点击类型 → 重新检查 → 分派到输入/确认/执行三条路。
     *
     * @param player     玩家
     * @param param      按钮
     * @param itemStack  已渲染的物品
     * @param gui        界面
     * @param limits     限制记录快照
     * @return 界面物品
     */
    private GuiItem createGuiItem(Player player, MenuButtonParam param, ItemStack itemStack,
                                  ChestGui gui, Map<Integer, MenuLimit> limits) {
        return new GuiItem(itemStack, event -> {
            event.setCancelled(true);

            Player clicker = (Player) event.getWhoClicked();
            // 每次点击都复制一份参数：点击类型必须跟着这一次点击走，
            // 而缓存的按钮对象是共享的，直接改会串味
            MenuButtonParam clickParam = param.copy();
            clickParam.setEventClickType(event.getClick());

            MenuConfig config = configProvider.get();
            MenuLimit limit = clickParam.getId() == 0 ? null : limits.get(clickParam.getId());

            // 重新检查：渲染到点击之间玩家状态可能已变（退出公会、花掉钱）
            String skipReason = checkForInput(clickParam);
            if (skipReason == null) {
                MenuChecker.Result result = MenuChecker.check(clicker, clickParam, limit, config);
                if (result != MenuChecker.Result.ALLOWED) {
                    executor.playSound(clicker, clickParam.resolveFailSound());
                    sendRejectMessage(clicker, clickParam, result);
                    return;
                }
            }

            // ---- 需要输入：保存上下文并关闭界面 ----
            if (TextUtil.isNotEmpty(clickParam.getInput())) {
                // 铁砧通道：不开聊天等待，直接弹一个铁砧界面收文本
                if (clickParam.isAnvilInput()) {
                    AnvilInputManager.begin(plugin, clicker, clickParam, gui, executor);
                    return;
                }
                InputSession session = new InputSession(clickParam, gui);
                InputManager.begin(clicker, session, config.getInputTimeout());
                clicker.closeInventory();
                clicker.sendMessage(LangUtil.message("inputPrompt"));
                clicker.sendMessage(LangUtil.message("inputExit"));
                return;
            }

            // ---- 需要确认：先开确认界面 ----
            if (clickParam.isConfirm()) {
                ConfirmGuiManager.open(plugin, configProvider, clicker, clickParam, gui, executor);
                return;
            }

            // ---- 直接执行 ----
            // 限购的"占名额"必须在执行动作之前完成，且必须由数据库原子裁决，
            // 否则同一界面上连点可以无限绕过 limit。查库是阻塞操作，放到异步线程；
            // 动作链本身要求实体线程，所以拿到结果后再切回去。
            if (clickParam.getId() == 0 || !needsNumberGate(clickParam, config)) {
                executor.execute(clicker, clickParam, limit, wrapFor(gui, player, clickParam));
                return;
            }

            final int max = clickParam.getLimit();
            final Player finalClicker = clicker;
            final ChestGui finalGui = gui;
            cn.listudio.exmenu.util.SchedulerUtil.runTaskAsynchronously(plugin, () -> {
                boolean granted = limitService.tryIncrementWithinLimit(
                        finalClicker.getName(), finalClicker.getUniqueId(), clickParam.getId(), max);
                cn.listudio.exmenu.util.SchedulerUtil.runAtEntity(plugin, finalClicker, () -> {
                    if (!granted) {
                        executor.playSound(finalClicker, clickParam.resolveFailSound());
                        sendRejectMessage(finalClicker, clickParam, MenuChecker.Result.LIMIT_REACHED);
                        return;
                    }
                    clickParam.setNumberClaimed(true);
                    if (limitCache != null) {
                        limitCache.invalidate(finalClicker.getUniqueId());
                    }
                    // 不再回查最新限制记录：execute() 内部并不消费 limit 参数
                    // （校验已前置、写入走原子累加），此前这里在实体线程同步查库
                    // 既违反"DB 必须异步"的规则，又给每次限购点击多加一次往返。
                    executor.execute(finalClicker, clickParam, null,
                            wrapFor(finalGui, player, clickParam));
                });
            });
        });
    }

    /**
     * 该按钮的次数限制是否需要在执行前原子占位。
     *
     * <p>只有"配置了 limit 且 action.limit 开关打开"才走原子门槛；
     * 纯 {@code limitHide} 只影响显示，不拦截点击。
     *
     * @param param  按钮
     * @param config 配置
     * @return true 表示需要原子占位
     */
    private static boolean needsNumberGate(MenuButtonParam param, MenuConfig config) {
        return param.getLimit() > 0 && config != null && config.isActionLimit();
    }

    /**
     * 判断该按钮是否因为输入类型而需要走输入流程。
     *
     * <p>用于区分"按钮声明了 input"与"input 只是个提示文本"。
     *
     * @param param 按钮
     * @return 需要输入时返回输入类型名，否则返回 null
     */
    private String checkForInput(MenuButtonParam param) {
        if (TextUtil.isEmpty(param.getInput())) {
            return null;
        }
        return InputTypeEnum.parse(param.getInputType()).name();
    }

    /**
     * 给玩家发送拒绝原因。
     *
     * <p>原版只有一句笼统的"操作未完成"，玩家根本不知道为什么点不动。
     * 这里按原因给出具体提示。
     *
     * @param player 玩家
     * @param param  按钮
     * @param result 检查结果
     */
    private void sendRejectMessage(Player player, MenuButtonParam param, MenuChecker.Result result) {
        switch (result) {
            case LIMIT_REACHED:
                player.sendMessage(TextUtil.isNotEmpty(param.getLimitMsg())
                        ? LangUtil.format("clickOperatorReason", "%reason%",
                                PlaceholderUtil.set(player, param.getLimitMsg()))
                        : LangUtil.message("clickOperatorReason"));
                break;
            case COOLING_DOWN:
                if (TextUtil.isNotEmpty(param.getCdMsg())) {
                    player.sendMessage(LangUtil.format("clickOperatorReason", "%reason%",
                            PlaceholderUtil.set(player, param.getCdMsg())));
                } else {
                    player.sendMessage(LangUtil.message("noTimeLimit"));
                }
                break;
            case CONDITION_FAILED:
                player.sendMessage(LangUtil.message("conditionFailure"));
                break;
            default:
                // 点击类型不匹配是正常的交互行为（例如用右键点了一个左键按钮），
                // 安静地忽略，不要打扰玩家
                break;
        }
    }

    /**
     * 用实例的 plugin 包装界面上下文。
     *
     * <p>调度必须要有一个所属插件实例（{@code Bukkit.getScheduler()} 会校验），
     * 而静态的 {@link #wrap(ChestGui, Player, MenuButtonParam)} 拿不到它 ——
     * 因此对外统一用这个实例方法，静态版本仅供确实没有插件实例的场景兜底。
     *
     * @param gui    界面
     * @param player 玩家
     * @param param  按钮
     * @return 上下文
     */
    public MenuExecutor.GuiContext wrapFor(ChestGui gui, Player player, MenuButtonParam param) {
        return wrap(plugin, gui, player, param);
    }

    /**
     * 把 IFramework 的界面包装成执行器认识的上下文。
     *
     * @param plugin 所属插件，用于调度；为 null 时回退到同步执行
     * @param gui    界面
     * @param player 玩家
     * @param param  按钮
     * @return 上下文
     */
    public static MenuExecutor.GuiContext wrap(Plugin plugin, ChestGui gui, Player player,
                                               MenuButtonParam param) {
        return new MenuExecutor.GuiContext() {
            @Override
            public void close() {
                // 必须回到玩家所在线程关界面，Folia 下从异步线程直接关会抛异常
                if (player == null) {
                    return;
                }
                if (plugin == null) {
                    // 没有插件实例就无法调度，这里直接同步关 —— 聊胜于无，
                    // 且仅发生在非插件环境（如测试）下
                    player.closeInventory();
                    return;
                }
                cn.listudio.exmenu.util.SchedulerUtil.runAtEntity(plugin, player, player::closeInventory);
            }

            @Override
            public void refresh() {
                if (gui == null) {
                    return;
                }
                // IFramework 的 update() 只重绘，不重开界面，适合做状态刷新
                if (plugin == null) {
                    gui.update();
                    return;
                }
                cn.listudio.exmenu.util.SchedulerUtil.runTask(plugin, gui::update);
            }
        };
    }

    /**
     * 兼容旧的静态调用形式（无插件实例）。
     *
     * @param gui    界面
     * @param player 玩家
     * @param param  按钮
     * @return 上下文
     */
    public static MenuExecutor.GuiContext wrap(ChestGui gui, Player player, MenuButtonParam param) {
        return wrap(null, gui, player, param);
    }

    // ------------------------------------------------------------------
    // 渲染辅助
    // ------------------------------------------------------------------

    /**
     * 权限过滤。
     *
     * @param player 玩家
     * @param param  按钮
     * @return true 表示玩家可以看见这个按钮
     */
    private boolean passesPermission(Player player, MenuButtonParam param) {
        if (!param.getPermission().isEmpty()) {
            boolean any = false;
            for (String permission : param.getPermission()) {
                if (player.hasPermission(permission)) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false;
            }
        }
        for (String permission : param.getNotPermission()) {
            if (player.hasPermission(permission)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断按钮是否应当隐藏。
     *
     * @param player 玩家
     * @param param  按钮
     * @param limits 限制记录
     * @return true 表示隐藏
     */
    private boolean shouldHide(Player player, MenuButtonParam param, Map<Integer, MenuLimit> limits) {
        MenuLimit limit = param.getId() == 0 ? null : limits.get(param.getId());

        // 次数用尽即隐藏
        if (param.isLimitHide() && param.getLimit() > 0) {
            int used = limit == null || limit.getNumber() == null ? 0 : limit.getNumber();
            if (used >= param.getLimit()) {
                return true;
            }
        }

        // 冷却中隐藏
        if (param.isCdHide() && MenuChecker.remainingCd(param, limit) > 0) {
            return true;
        }

        // 条件隐藏
        return MenuChecker.checkConditionsHide(player, param.getConditionsHide());
    }

    /**
     * 构建按钮物品。
     *
     * @param player      玩家
     * @param param       按钮
     * @param itemLibrary 物品库
     * @param limits      限制记录
     * @param papiCache   渲染级变量替换缓存（同一次渲染内共享）
     * @return 物品；不应渲染时返回 null
     */
    private ItemStack buildItem(Player player, MenuButtonParam param,
                                Map<Integer, cn.listudio.exmenu.enter.MenuItem> itemLibrary,
                                Map<Integer, MenuLimit> limits,
                                Map<String, String> papiCache) {
        // ---- 优先从物品库取 ----
        if (param.getId() > 0) {
            cn.listudio.exmenu.enter.MenuItem menuItem = itemLibrary.get(param.getId());
            if (menuItem != null) {
                ItemStack fromLibrary = ItemStackUtil.deserialize(menuItem.getItemJson());
                if (fromLibrary != null) {
                    return applyDynamic(player, param, fromLibrary, limits, papiCache);
                }
            }
            // 物品库里没有：回退到材质渲染，而不是直接消失。
            // 让按钮"看起来不对"比让它"凭空消失"好排查得多。
        }

        String material = pset(papiCache, player, param.getMaterial());
        if (TextUtil.isEmpty(material)) {
            return null;
        }

        ItemStack itemStack = ItemStackUtil.parse(material);
        if (itemStack == null) {
            plugin.getLogger().warning("无法解析材质: " + material + " (菜单 " + param.getMenuName() + ")");
            return null;
        }

        // AIR 是合法配置，表示"这个槽位留空但保留点击区域"
        if (param.isAir()) {
            itemStack = new ItemStack(org.bukkit.Material.AIR);
        }

        return applyDynamic(player, param, itemStack, limits, papiCache);
    }

    /**
     * 带渲染级缓存的 PAPI 替换。
     *
     * <p>只缓存含 {@code %} 的文本 —— 不含变量的字符串在
     * {@link PlaceholderUtil#set} 里本来就是直接返回，缓存反而多一次哈希查找。
     *
     * @param cache  本次渲染的缓存
     * @param player 玩家
     * @param text   原始文本
     * @return 替换后的文本
     */
    private static String pset(Map<String, String> cache, Player player, String text) {
        if (text == null || text.indexOf('%') < 0) {
            return PlaceholderUtil.set(player, text);
        }
        String cached = cache.get(text);
        if (cached != null) {
            return cached;
        }
        String resolved = PlaceholderUtil.set(player, text);
        cache.put(text, resolved);
        return resolved;
    }

    /**
     * 带渲染级缓存的 PAPI 替换（列表版）。
     *
     * @param cache  本次渲染的缓存
     * @param player 玩家
     * @param lines  原始文本列表
     * @return 替换后的列表，永不返回 null
     */
    private static List<String> psetList(Map<String, String> cache, Player player, List<String> lines) {
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            result.add(pset(cache, player, line));
        }
        return result;
    }

    /**
     * 套用动态字段：名称、描述、数量。
     *
     * <p>这些字段每次渲染都要重新求值，因为它们通常引用了会变的变量
     * （{@code %player_level%}、剩余次数等）。
     *
     * @param player    玩家
     * @param param     按钮
     * @param itemStack 基础物品
     * @param limits    限制记录
     * @param papiCache 渲染级变量替换缓存
     * @return 处理后的物品
     */
    private ItemStack applyDynamic(Player player, MenuButtonParam param, ItemStack itemStack,
                                   Map<Integer, MenuLimit> limits, Map<String, String> papiCache) {
        // ---- 名称：dynamicName 优先，它允许引用 PAPI ----
        String name = TextUtil.isNotEmpty(param.getDynamicName())
                ? pset(papiCache, player, param.getDynamicName())
                : param.getName();

        // ---- 描述：dynamicLore 优先 ----
        List<String> lore = !param.getDynamicLore().isEmpty()
                ? psetList(papiCache, player, param.getDynamicLore())
                : param.getLore();

        // ---- 数量：dynamicAmount 优先，但 0 要回退到配置的 amount ----
        int amount = param.getAmount();
        if (TextUtil.isNotEmpty(param.getDynamicAmount())) {
            String resolved = pset(papiCache, player, param.getDynamicAmount());
            Double value = TextUtil.evaluate(resolved);
            // 动态数量算出 0 或负数时回退，而不是让物品消失 ——
            // 原版直接设成 0，导致物品数量为 0 时渲染异常
            if (value != null && value > 0) {
                amount = (int) Math.min(64, Math.round(value));
            }
        }

        // 条件/冷却/次数信息追加到描述末尾，让玩家知道为什么点不动
        List<String> extra = buildStatusLore(player, param, limits);
        if (!extra.isEmpty()) {
            List<String> merged = new ArrayList<>(lore);
            merged.addAll(extra);
            lore = merged;
        }

        return ItemStackUtil.apply(itemStack, name, lore, param.isEnchant(),
                param.getCustomModelData(), param.isHideFlag(), param.getUnbreakable(),
                param.getTooltipStyle(), amount);
    }

    /**
     * 生成状态提示行。
     *
     * @param player 玩家
     * @param param  按钮
     * @param limits 限制记录
     * @return 提示行列表，无则返回空列表
     */
    private List<String> buildStatusLore(Player player, MenuButtonParam param, Map<Integer, MenuLimit> limits) {
        List<String> result = new ArrayList<>();
        if (param.getId() == 0) {
            return result;
        }
        MenuLimit limit = limits.get(param.getId());
        if (limit == null) {
            return result;
        }

        long remainingCd = MenuChecker.remainingCd(param, limit);
        if (remainingCd > 0) {
            result.add(LangUtil.plain("noTimeLimit").replace("%time%",
                    String.valueOf((remainingCd + 999) / 1000)));
        }
        if (param.getLimit() > 0) {
            int remaining = MenuChecker.remaining(param, limit);
            result.add(LangUtil.plain("clickOperatorReason").replace("%reason%",
                    remaining + "/" + param.getLimit()));
        }
        return result;
    }

    /**
     * 检查菜单开启权限。
     *
     * @param player   玩家
     * @param menuFile 菜单文件名
     * @param config   配置
     * @return true 表示有权限
     */
    private boolean checkPermission(Player player, String menuFile, MenuConfig config) {
        // 管理员预览时不受菜单权限限制
        if (MenuSession.isAdminView(player)) {
            return true;
        }
        if (!config.isPermission()) {
            return true;
        }
        String permission = config.getPermissionMap().get(menuFile);
        if (TextUtil.isEmpty(permission)) {
            return true;
        }
        return player.hasPermission(permission);
    }

    /**
     * 检查世界黑名单。
     *
     * @param player 玩家
     * @param config 配置
     * @return true 表示当前世界被禁止
     */
    private boolean isWorldBlocked(Player player, MenuConfig config) {
        if (MenuSession.isAdminView(player)) {
            return false;
        }
        List<String> noWorld = config.getNoWorld();
        if (noWorld == null || noWorld.isEmpty()) {
            return false;
        }
        String world = player.getWorld().getName();
        for (String blocked : noWorld) {
            if (blocked != null && blocked.equalsIgnoreCase(world)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把大小规整为 9 的倍数且在 9~54 之间。
     *
     * <p>原版直接把这个值交给服务端，写错就会在打开界面时抛异常。
     *
     * @param size 配置里的大小
     * @return 合法大小
     */
    private static int normalizeSize(int size) {
        if (size < 9) {
            return 9;
        }
        if (size > 54) {
            return 54;
        }
        return (size + 8) / 9 * 9;
    }

    /**
     * 便捷方法：以管理员身份预览菜单。
     *
     * <p>历史上还有"refresh(Player) 按标题回查菜单重渲染"与"openAsPlayer"两个方法，
     * 均无任何调用点已删除 —— 输入完成后的界面刷新走各自的收尾逻辑
     * （{@code InputManager.finish} 与 {@code ConfirmGuiManager}），
     * 不再依赖"用标题字符串猜菜单"这种脆弱匹配。
     *
     * @param player   玩家
     * @param menuName 菜单名
     */
    public void openAsAdmin(Player player, String menuName) {
        MenuSession.setAdminView(player);
        open(player, menuName);
    }

    /**
     * 按按钮 id 在指定菜单里查找按钮。
     *
     * <p>供 {@code MenuExecutor.SubActionHandler} 使用：{@code [action] 菜单名:按钮id}
     * 的语义是"再执行一个按钮"，而按钮在配置里是用 {@code id} 标识的，
     * 不是列表下标。
     *
     * @param menuFile 菜单文件名，可带或不带 {@code .yml}
     * @param id       按钮 id
     * @return 按钮；菜单不存在或没有该 id 时返回 null
     */
    public MenuButtonParam findButtonById(String menuFile, int id) {
        MenuConfig config = configProvider.get();
        if (config == null || menuFile == null) {
            return null;
        }
        String name = menuFile.toLowerCase().endsWith(".yml") ? menuFile : menuFile + ".yml";
        YamlConfiguration menu = config.getMenu(name);
        if (menu == null) {
            return null;
        }
        for (MenuButtonParam param : parseButtons(name, menu)) {
            if (param.getId() == id) {
                return param;
            }
        }
        return null;
    }

    /**
     * 按按钮 id 在<b>所有</b>菜单里查找按钮。
     *
     * <p>供 PAPI 变量使用（{@code %exmenu_limit_remaining_<id>%} 只带 id，不带菜单名）。
     * id 约定全局唯一，因此返回第一个命中即可；解析结果走按钮缓存，成本是一次哈希遍历。
     *
     * @param id 按钮 id
     * @return 按钮；不存在时返回 null
     */
    public MenuButtonParam findButtonByGlobalId(int id) {
        if (id == 0) {
            return null;
        }
        MenuConfig config = configProvider.get();
        if (config == null) {
            return null;
        }
        for (Map.Entry<String, YamlConfiguration> entry
                : config.getMenuConfigMap().entrySet()) {
            for (MenuButtonParam param : parseButtons(entry.getKey(), entry.getValue())) {
                if (param.getId() == id) {
                    return param;
                }
            }
        }
        return null;
    }

    /**
     * 校验一个菜单的配置，返回问题列表。
     *
     * <p>供 {@code /exmenu check} 使用。把配置错误在启动/检查阶段暴露出来，
     * 比等玩家点出问题再排查要好得多。
     *
     * @param menuFile 菜单文件名
     * @return 问题描述列表，空列表表示没有问题
     */
    public List<String> validate(String menuFile) {
        List<String> problems = new ArrayList<>();
        MenuConfig config = configProvider.get();
        YamlConfiguration menu = config.getMenu(menuFile);
        if (menu == null) {
            problems.add("菜单不存在: " + menuFile);
            return problems;
        }

        int rawSize = menu.getInt("size", 27);
        if (rawSize != normalizeSize(rawSize)) {
            problems.add("size=" + rawSize + " 不是 9 的倍数，已规整为 " + normalizeSize(rawSize));
        }

        List<MenuButtonParam> buttons = parseButtons(
                menuFile.toLowerCase().endsWith(".yml") ? menuFile : menuFile + ".yml", menu);

        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < buttons.size(); i++) {
            MenuButtonParam param = buttons.get(i);
            String where = "buttons[" + i + "]";

            // 空行检查：原版遇到空行会静默中断整条动作链
            for (int j = 0; j < param.getCommands().size(); j++) {
                String command = param.getCommands().get(j);
                if (command == null || command.trim().isEmpty()) {
                    problems.add(where + ".commands 第 " + (j + 1) + " 行为空，会中断整条动作链");
                } else if (cn.listudio.exmenu.constants.CommandTypeEnum.match(command) == null) {
                    problems.add(where + ".commands 第 " + (j + 1) + " 行标签无法识别: " + command);
                }
            }

            // id 唯一性：原版重复 id 会导致限制记录互相串
            if (param.getId() > 0) {
                if (ids.contains(param.getId())) {
                    problems.add(where + ".id=" + param.getId() + " 与前面的按钮重复");
                } else {
                    ids.add(param.getId());
                }
            }

            for (Integer slot : param.getSlots()) {
                if (slot >= normalizeSize(rawSize)) {
                    problems.add(where + ".slots 中的 " + slot + " 超出界面范围");
                }
            }
        }
        return problems;
    }
}
