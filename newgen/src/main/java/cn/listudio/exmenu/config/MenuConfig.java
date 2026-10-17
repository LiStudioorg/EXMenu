package cn.listudio.exmenu.config;

import cn.listudio.exmenu.util.LangUtil;
import cn.listudio.exmenu.util.YamlUtil;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件配置的运行时快照。
 *
 * <p>启动与 {@code /exmenu reload} 时从磁盘重建整个对象，之后所有代码只读这个快照 ——
 * 不做运行时部分更新，因为配置项之间存在耦合（例如改了 {@code action.cd} 就可能在
 * 其它地方假设冷却已归一化）。整体替换比增量更新更不容易出错。
 *
 * <p>本类的实例在 reload 时被整体替换，因此读配置的地方应当每次从
 * {@code MenuPlugin.getConfig()} 现取，而不要长期持有引用。
 */
public final class MenuConfig implements cn.listudio.exmenu.core.MenuChecker.ConfigAccessor {

    /** 语言代码。 */
    private String language = "zh_CN";

    /** 禁止使用菜单的世界名（小写）。 */
    private List<String> noWorld;

    /** 是否自动分配物品库 id。 */
    private boolean autoCreateId = true;

    /** 管理员绕过权限的权限节点。 */
    private String adminOpenPermission = "exmenu.adminOpen";

    /** 是否检查更新。 */
    private boolean checkUpdate = true;

    /** 首次启动时是否给管理员发送引导消息。 */
    private boolean firstRunHelp = true;

    /** 时钟物品配置。 */
    private boolean clockEnable;
    private String clockMenu;
    private String clockItem;
    private String clockName;
    private int clockSlot = 8;

    /** Shift+F 配置。 */
    private boolean shiftEnable;
    private String shiftMenu;

    /** 动作开关：是否允许冷却。 */
    private boolean actionCd = true;

    /** 动作开关：是否允许次数限制。 */
    private boolean actionLimit = true;

    /** 动作开关：是否允许金钱动作。 */
    private boolean actionMoney = true;

    /** 动作开关：是否允许点券动作。 */
    private boolean actionPoint = true;

    /** 是否需要菜单打开权限。 */
    private boolean permission;

    /** 菜单文件映射：文件名（含 .yml） → 配置。 */
    private Map<String, YamlConfiguration> menuConfigMap = new LinkedHashMap<>();

    /** 命令映射：命令名 → 菜单文件名。 */
    private Map<String, String> commandMap = new HashMap<>();

    /** 物品跳转映射：物品材质（小写） → 菜单文件名。 */
    private Map<String, String> itemMap = new HashMap<>();

    /** 权限映射：菜单文件名 → 所需权限。 */
    private Map<String, String> permissionMap = new HashMap<>();

    /** 输入等待超时（秒）。 */
    private int inputTimeout = 30;

    /** 数据目录。 */
    private File dataFolder;

    /** 插件实例。 */
    private transient Plugin plugin;

    /**
     * 就地重载配置。
     *
     * <p>设计说明：{@link #load(Plugin)} 会造一个全新对象，但调用方按接口只持有
     * {@code ConfigProvider}，无法把新对象替换进去。因此这里改为把同一个实例的字段
     * 用新读到的值覆盖一遍 —— 效果等价于 {@code load()}，而引用保持不变。
     *
     * <p>字段赋值放在最后统一进行：如果中途读文件抛异常，旧配置仍然完整可用，
     * 不会出现"配置读了一半"的半残状态。
     *
     * @param plugin 插件实例
     * @throws RuntimeException 读取失败时抛出，由调用方提示玩家
     */
    public void reload(Plugin plugin) {
        MenuConfig fresh = load(plugin);
        this.plugin = plugin;
        this.dataFolder = fresh.dataFolder;

        this.language = fresh.language;
        this.noWorld = fresh.noWorld;
        this.autoCreateId = fresh.autoCreateId;
        this.adminOpenPermission = fresh.adminOpenPermission;
        this.checkUpdate = fresh.checkUpdate;
        this.firstRunHelp = fresh.firstRunHelp;
        this.permission = fresh.permission;
        this.inputTimeout = fresh.inputTimeout;

        this.clockEnable = fresh.clockEnable;
        this.clockMenu = fresh.clockMenu;
        this.clockItem = fresh.clockItem;
        this.clockName = fresh.clockName;
        this.clockSlot = fresh.clockSlot;

        this.shiftEnable = fresh.shiftEnable;
        this.shiftMenu = fresh.shiftMenu;

        this.actionCd = fresh.actionCd;
        this.actionLimit = fresh.actionLimit;
        this.actionMoney = fresh.actionMoney;
        this.actionPoint = fresh.actionPoint;

        this.menuConfigMap = fresh.menuConfigMap;
        this.commandMap = fresh.commandMap;
        this.itemMap = fresh.itemMap;
        this.permissionMap = fresh.permissionMap;

        this.itemGuiConfig = fresh.itemGuiConfig;
        this.confirmGuiConfig = fresh.confirmGuiConfig;
    }

    /**
     * 从磁盘加载全部配置。
     *
     * @param plugin 插件实例
     * @return 配置快照
     */
    public static MenuConfig load(Plugin plugin) {
        MenuConfig config = new MenuConfig();
        config.plugin = plugin;
        config.dataFolder = plugin.getDataFolder();

        plugin.saveDefaultConfig();
        YamlConfiguration root = YamlUtil.load(new File(config.dataFolder, "config.yml"));
        YamlConfiguration defaults = YamlUtil.loadResource(plugin, "config.yml");
        // 磁盘配置缺项时用 jar 内默认值补齐，这样升级不会因新增配置项而报错
        root.setDefaults(defaults);

        config.language = root.getString("language", "zh_CN");
        config.noWorld = root.getStringList("noWorld");
        config.autoCreateId = root.getBoolean("autoCreateId", true);
        config.adminOpenPermission = root.getString("adminOpenPermission", "exmenu.adminOpen");
        config.checkUpdate = root.getBoolean("isCheckUpdate", true);
        config.firstRunHelp = root.getBoolean("firstRunHelp", true);
        config.permission = root.getBoolean("permission", false);
        config.inputTimeout = root.getInt("inputTimeout", 30);

        config.clockEnable = root.getBoolean("clock.enable", false);
        config.clockMenu = root.getString("clock.menu", "");
        config.clockItem = root.getString("clock.item", "CLOCK");
        config.clockName = root.getString("clock.name", "&e菜单");
        config.clockSlot = root.getInt("clock.slot", 8);

        config.shiftEnable = root.getBoolean("shift.F.enable", false);
        config.shiftMenu = root.getString("shift.F.menu", "");

        // action.* 是"点击类型选择器"（ALL / LEFT / RIGHT / MIDDLE），不是布尔开关。
        // 早期实现用 getBoolean() 读取，遇到配置里的 "ALL" 会返回 false ——
        // 也就是开箱即用时冷却、次数限制和经济动作全部被静默禁用。
        // 这里按语义解析：只有显式限定为某个按键时才认为"该按键之外不生效"。
        config.actionCd = isActionEnabled(root, "action.cd");
        config.actionLimit = isActionEnabled(root, "action.limit");
        config.actionMoney = isActionEnabled(root, "action.money");
        config.actionPoint = isActionEnabled(root, "action.point");

        LangUtil.load(plugin, config.dataFolder, config.language);
        config.loadMenus();
        config.loadGuiConfigs();

        return config;
    }

    /**
     * 解析 {@code action.*} 开关。
     *
     * <p>该配置项接受两种写法：
     * <ul>
     *   <li><b>点击类型</b>：{@code ALL} / {@code LEFT} / {@code RIGHT} / {@code MIDDLE}，
     *       其中 {@code ALL} 表示全部生效</li>
     *   <li><b>布尔</b>：{@code true} / {@code false}（兼容旧写法）</li>
     * </ul>
     *
     * <p>关键点：<b>只有显式的 {@code false} 才表示关闭</b>。任何无法识别的值
     * （包括 {@code ALL} 这类点击类型）都按"开启"处理 —— 保守地保持功能可用，
     * 而不是因为解析失败把功能悄悄关掉。
     *
     * @param root 根配置
     * @param path 键路径
     * @return true 表示该动作启用
     */
    private static boolean isActionEnabled(YamlConfiguration root, String path) {
        Object raw = root.get(path);
        if (raw == null) {
            return true;
        }
        if (raw instanceof Boolean) {
            return (Boolean) raw;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return true;
        }
        // 显式关闭：false / off / no / 0 / disable
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if ("false".equals(lower) || "off".equals(lower) || "no".equals(lower)
                || "0".equals(lower) || "disable".equals(lower) || "disabled".equals(lower)) {
            return false;
        }
        return true;
    }

    /**
     * 扫描 {@code menu/} 目录，建立菜单、命令、物品跳转、权限四张映射表。
     *
     * <p>目录不存在时会先从 jar 释放示例菜单。
     *
     * <p>公开可见是因为新增菜单文件后（{@code /exmenu create}、{@code /exmenu addItem}）
     * 需要在不重建整个配置快照的前提下让新文件立刻生效。
     */
    public void loadMenus() {
        File menuFolder = new File(dataFolder, "menu");
        if (!menuFolder.exists() && !menuFolder.mkdirs()) {
            plugin.getLogger().warning("无法创建菜单目录: " + menuFolder.getAbsolutePath());
            return;
        }

        Map<String, YamlConfiguration> menus = new LinkedHashMap<>();
        Map<String, String> commands = new HashMap<>();
        Map<String, String> items = new HashMap<>();
        Map<String, String> permissions = new HashMap<>();

        File[] files = menuFolder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (!file.isFile() || !file.getName().toLowerCase().endsWith(".yml")) {
                    continue;
                }
                String fileName = file.getName();
                YamlConfiguration menu = YamlUtil.load(file);
                menus.put(fileName, menu);

                // 打开命令：一个菜单可以绑定多个命令
                for (String command : menu.getStringList("openCommand")) {
                    if (command != null && !command.trim().isEmpty()) {
                        commands.put(command.trim().toLowerCase(), fileName);
                    }
                }

                // 物品跳转：手持该物品右键空气即打开菜单
                for (String item : menu.getStringList("openItem")) {
                    if (item != null && !item.trim().isEmpty()) {
                        items.put(item.trim().toLowerCase(), fileName);
                    }
                }

                String permission = menu.getString("permission", "");
                if (permission != null && !permission.trim().isEmpty()) {
                    permissions.put(fileName, permission.trim());
                }
            }
        }

        this.menuConfigMap = menus;
        this.commandMap = commands;
        this.itemMap = items;
        this.permissionMap = permissions;
    }

    /** 物品库界面配置。 */
    private YamlConfiguration itemGuiConfig = new YamlConfiguration();

    /** 确认界面配置。 */
    private YamlConfiguration confirmGuiConfig = new YamlConfiguration();

    /**
     * 加载 {@code gui/} 下的两个界面配置。
     *
     * <p>这两个文件缺失时会从 jar 释放默认版本 —— 它们没有合理的代码默认值，
     * 因为里面全是给玩家看的文字。
     */
    private void loadGuiConfigs() {
        File guiFolder = new File(dataFolder, "gui");
        if (!guiFolder.exists() && !guiFolder.mkdirs()) {
            plugin.getLogger().warning("无法创建 GUI 配置目录: " + guiFolder.getAbsolutePath());
        }

        itemGuiConfig = loadOrExtract("gui/item.yml");
        confirmGuiConfig = loadOrExtract("gui/confirm.yml");
    }

    /**
     * 读取配置文件，不存在时先从 jar 释放。
     *
     * @param resource 相对于数据目录的路径，同时也是 jar 内资源路径
     * @return 配置对象
     */
    private YamlConfiguration loadOrExtract(String resource) {
        File file = new File(dataFolder, resource);
        if (!file.exists()) {
            plugin.saveResource(resource, false);
        }
        YamlConfiguration config = YamlUtil.load(file);
        config.setDefaults(YamlUtil.loadResource(plugin, resource));
        return config;
    }

    // ------------------------------------------------------------------
    // 访问器
    // ------------------------------------------------------------------

    public String getLanguage() {
        return language;
    }

    public List<String> getNoWorld() {
        return noWorld;
    }

    public boolean isAutoCreateId() {
        return autoCreateId;
    }

    public String getAdminOpenPermission() {
        return adminOpenPermission;
    }

    public boolean isCheckUpdate() {
        return checkUpdate;
    }

    /**
     * 首次启动是否向管理员发送引导消息。
     *
     * @return true 表示发送
     */
    public boolean isFirstRunHelp() {
        return firstRunHelp;
    }

    public boolean isClockEnable() {
        return clockEnable;
    }

    public String getClockMenu() {
        return clockMenu;
    }

    public String getClockItem() {
        return clockItem;
    }

    public String getClockName() {
        return clockName;
    }

    public int getClockSlot() {
        return clockSlot;
    }

    public boolean isShiftEnable() {
        return shiftEnable;
    }

    public String getShiftMenu() {
        return shiftMenu;
    }

    public boolean isActionCd() {
        return actionCd;
    }

    @Override
    public boolean isActionLimit() {
        return actionLimit;
    }

    @Override
    public boolean isActionMoney() {
        return actionMoney;
    }

    @Override
    public boolean isActionPoint() {
        return actionPoint;
    }

    public boolean isPermission() {
        return permission;
    }

    public Map<String, YamlConfiguration> getMenuConfigMap() {
        return menuConfigMap;
    }

    public Map<String, String> getCommandMap() {
        return commandMap;
    }

    public Map<String, String> getItemMap() {
        return itemMap;
    }

    public Map<String, String> getPermissionMap() {
        return permissionMap;
    }

    public YamlConfiguration getItemGuiConfig() {
        return itemGuiConfig;
    }

    public YamlConfiguration getConfirmGuiConfig() {
        return confirmGuiConfig;
    }

    public int getInputTimeout() {
        return inputTimeout;
    }

    public File getDataFolder() {
        return dataFolder;
    }

    /**
     * 按文件名取菜单配置，自动补 {@code .yml} 后缀。
     *
     * @param menu 菜单文件名，可带或不带后缀
     * @return 配置；不存在时返回 null
     */
    public YamlConfiguration getMenu(String menu) {
        if (menu == null || menu.isEmpty()) {
            return null;
        }
        String name = menu.toLowerCase().endsWith(".yml") ? menu : menu + ".yml";
        return menuConfigMap.get(name);
    }

    /**
     * 列出全部菜单文件名。
     *
     * @return 文件名列表（含 .yml）
     */
    public List<String> listMenus() {
        return new java.util.ArrayList<>(menuConfigMap.keySet());
    }
}
