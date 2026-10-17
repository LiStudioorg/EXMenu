package cn.listudio.exmenu.inventory;

import cn.listudio.exmenu.config.MenuConfig;
import cn.listudio.exmenu.util.ItemStackUtil;
import cn.listudio.exmenu.util.LangUtil;
import cn.listudio.exmenu.util.SchedulerUtil;
import cn.listudio.exmenu.util.TextUtil;
import cn.listudio.exmenu.util.YamlPatch;
import cn.listudio.exmenu.util.YamlUtil;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.AnvilGui;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import com.github.stefvanschie.inventoryframework.pane.util.Slot;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 配置中心界面（{@code /exmenu settings}）。
 *
 * <p>面向"看不懂 YAML"的服主：把 {@code config.yml} 里最常用的开关做成
 * 可点击的按钮 —— 绿/亮=开、红=关、点击轮换取值，改完立即写盘并就地生效。
 *
 * <p>写盘走 {@link YamlPatch} 的行内替换而不是 {@code YamlConfiguration.save()}：
 * 后者会把配置文件里的中文说明整体抹掉，而那份说明正是新手唯一的说明书。
 *
 * <p>无跨会话状态：界面每次点击后重建，天然避免"两个管理员同开一个界面"
 * 造成状态错乱 —— 后保存者以磁盘值为准。
 *
 * <p>图标一律用字符串材质名（经 XSeries 解析），不直接引用 {@code Material}
 * 枚举常量 —— 1.13~1.16 服务端没有 {@code LIME_DYE} 这类新名字，
 * 枚举引用会在旧服务端上抛 {@code NoSuchFieldError}。
 */
public final class SettingsGuiManager {

    /** action.* 的取值循环序列。 */
    private static final List<String> ACTION_VALUES =
            Collections.unmodifiableList(Arrays.asList("ALL", "LEFT", "RIGHT", "MIDDLE", "false"));

    /** 语言循环序列。 */
    private static final List<String> LANGUAGES =
            Collections.unmodifiableList(Arrays.asList("zh_CN", "zh_TW", "en_US"));

    /** 铁砧输出槽。 */
    private static final int RESULT_SLOT = 2;

    private final Plugin plugin;
    private final ConfigProvider configProvider;
    private final MenuGuiManager guiManager;

    /**
     * 构造。
     *
     * @param plugin         插件实例
     * @param configProvider 配置提供者
     * @param guiManager     界面管理器（重载后需要丢弃按钮缓存）
     */
    public SettingsGuiManager(Plugin plugin, ConfigProvider configProvider, MenuGuiManager guiManager) {
        this.plugin = plugin;
        this.configProvider = configProvider;
        this.guiManager = guiManager;
    }

    /**
     * 打开配置中心。
     *
     * @param player 玩家
     */
    public void open(Player player) {
        MenuConfig config = configProvider.get();
        if (config == null) {
            return;
        }

        ChestGui gui = new ChestGui(4, TextUtil.color("&8EXMenu 配置中心"));
        gui.setOnGlobalClick(event -> event.setCancelled(true));
        StaticPane pane = new StaticPane(9, 4);

        // ---- 第一行：功能开关 ----
        toggle(pane, player, 10, "permission",
                config.isPermission(), "REDSTONE_BLOCK", "LIME_DYE",
                "&c菜单权限: 关", "&a菜单权限: 开",
                Arrays.asList("&7开启后", "&7菜单文件的 permission 节点才生效"));

        toggle(pane, player, 12, "clock.enable",
                config.isClockEnable(), "REDSTONE_BLOCK", "CLOCK",
                "&c时钟物品: 关", "&a时钟物品: 开",
                Arrays.asList("&7开启后玩家进服获得时钟物品", "&7右键打开 clock.menu 指定的菜单"));

        toggle(pane, player, 14, "autoCreateId",
                config.isAutoCreateId(), "REDSTONE_BLOCK", "LIME_DYE",
                "&c自动 id: 关", "&a自动 id: 开",
                Arrays.asList("&7create / edit 生成按钮时", "&7自动分配唯一 id（限购/冷却的前提）"));

        // ---- 第二行：语言 + 限制生效键 ----
        String lang = config.getLanguage();
        item(pane, 19, "PAPER",
                "&e语言: " + lang,
                Arrays.asList("&7点击切换 zh_CN / zh_TW / en_US"),
                event -> {
                    int idx = LANGUAGES.indexOf(lang);
                    String next = LANGUAGES.get((idx < 0 ? 0 : idx + 1) % LANGUAGES.size());
                    if (save(player, "language", next)) {
                        open(player);
                    }
                });

        actionCycle(pane, player, 21, "action.cd");
        actionCycle(pane, player, 22, "action.limit");
        actionCycle(pane, player, 23, "action.money");
        actionCycle(pane, player, 25, "action.point");

        // ---- 第三行：数值 ----
        item(pane, 31, "CLOCK",
                "&e输入超时: " + config.getInputTimeout() + " 秒",
                Arrays.asList("&7聊天输入的等待秒数", "&70 = 不超时"),
                event -> promptNumber(player, "inputTimeout", "输入超时（秒）", 0, 600));

        // ---- 第四行：说明与关闭 ----
        item(pane, 27, "BOOK", "&b怎么看？",
                Arrays.asList("&7绿/亮 = 开，红 = 关", "&7点击按钮即可切换",
                        "&7改动立即写入 config.yml 并生效"), null);

        item(pane, 35, "BARRIER", "&c关闭",
                Collections.emptyList(), event -> player.closeInventory());

        gui.addPane(Slot.fromIndex(0), pane);
        gui.show(player);
    }

    /**
     * 布尔开关按钮。
     *
     * @param pane     面板
     * @param player   查看者
     * @param slot     槽位
     * @param path     配置路径
     * @param on       当前值
     * @param offMat   关闭材质名
     * @param onMat    开启材质名
     * @param offName  关闭名称
     * @param onName   开启名称
     * @param lore     说明行
     */
    private void toggle(StaticPane pane, Player player, int slot, String path, boolean on,
                        String offMat, String onMat, String offName, String onName,
                        List<String> lore) {
        List<String> hint = new ArrayList<>(lore);
        hint.add(TextUtil.color("&7点击切换：") + (on ? "&c关" : "&a开"));
        String name = on ? onName : offName;
        item(pane, slot, on ? onMat : offMat, name, hint,
                event -> {
                    if (save(player, path, String.valueOf(!on))) {
                        open(player);
                    }
                });
    }

    /**
     * action.* 循环按钮：ALL → LEFT → RIGHT → MIDDLE → 关闭 → ALL。
     *
     * @param pane   面板
     * @param player 查看者
     * @param slot   槽位
     * @param path   配置路径（action.xxx）
     */
    private void actionCycle(StaticPane pane, Player player, int slot, String path) {
        String current = readRaw(path);
        int rawIndex = ACTION_VALUES.indexOf(current);
        final int index = rawIndex < 0 ? 0 : rawIndex;
        boolean off = "false".equals(current);
        List<String> lore = new ArrayList<>();
        lore.add(off ? "&7当前：不生效" : "&7当前对该键点击生效");
        lore.add("&7点击轮换");
        item(pane, slot, off ? "REDSTONE_BLOCK" : "LIME_DYE",
                "&e生效键: " + labelOf(current),
                lore,
                event -> {
                    String next = ACTION_VALUES.get((index + 1) % ACTION_VALUES.size());
                    if (saveRaw(player, path, next)) {
                        open(player);
                    }
                });
    }

    /**
     * 生效键值的中文别名。
     *
     * @param value 值
     * @return 别名
     */
    private static String labelOf(String value) {
        switch (value) {
            case "ALL":
                return "全部键";
            case "LEFT":
                return "左键";
            case "RIGHT":
                return "右键";
            case "MIDDLE":
                return "中键";
            default:
                return "关闭";
        }
    }

    /**
     * 通用按钮。
     *
     * @param pane     面板
     * @param slot     槽位
     * @param material 材质名（字符串，经 XSeries 解析）
     * @param name     名称（含 &amp; 颜色码）
     * @param lore     说明行（含 &amp; 颜色码）
     * @param onClick  点击回调，null 表示纯展示
     */
    private void item(StaticPane pane, int slot, String material, String name,
                      List<String> lore,
                      java.util.function.Consumer<org.bukkit.event.inventory.InventoryClickEvent> onClick) {
        ItemStack item = ItemStackUtil.build(material, name, lore, false, 0, true, null, null);
        if (item == null) {
            return;
        }
        if (onClick == null) {
            pane.addItem(new GuiItem(item), Slot.fromIndex(slot));
        } else {
            pane.addItem(new GuiItem(item, onClick), Slot.fromIndex(slot));
        }
    }

    /**
     * 铁砧数字输入（限定范围）。
     *
     * @param player 玩家
     * @param path   保存路径
     * @param prompt 提示标题
     * @param min    最小值
     * @param max    最大值
     */
    private void promptNumber(Player player, String path, String prompt, int min, int max) {
        AnvilGui gui = new AnvilGui(TextUtil.color("&8" + prompt + "（" + min + "~" + max + "）"), plugin);
        ItemStack base = ItemStackUtil.build("PAPER", "&e输入后点右侧输出格确认",
                Collections.emptyList(), false, 0, true, null, null);
        if (base != null) {
            gui.getFirstItemComponent().setItem(new GuiItem(base), Slot.fromIndex(0));
        }
        AtomicReference<String> typed = new AtomicReference<>("");
        AtomicBoolean done = new AtomicBoolean(false);
        gui.setOnNameInputChanged(t -> typed.set(t == null ? "" : t));
        gui.setOnGlobalClick(event -> {
            event.setCancelled(true);
            if (event.getClickedInventory() == null
                    || !event.getClickedInventory().equals(gui.getInventory())
                    || event.getRawSlot() != RESULT_SLOT) {
                return;
            }
            int value;
            try {
                value = Integer.parseInt(typed.get().trim());
            } catch (NumberFormatException e) {
                player.sendMessage(LangUtil.message("noNumber"));
                return;
            }
            if (value < min || value > max) {
                player.sendMessage(LangUtil.format("configError", "%reason%",
                        "取值范围 " + min + "~" + max));
                return;
            }
            done.set(true);
            if (save(player, path, String.valueOf(value))) {
                player.closeInventory();
                open(player);
            }
        });
        gui.setOnClose(event -> {
            if (done.get()) {
                return;
            }
            done.set(true);
            SchedulerUtil.runAtEntity(plugin, player, () -> {
                if (player.isOnline()) {
                    open(player);
                }
            });
        });
        gui.show(player);
    }

    /**
     * 从磁盘读 config.yml 里某路径的原始字符串（快照里没存的值用，如 action.*）。
     *
     * @param path 路径
     * @return 原始值（规整为大写或 false）
     */
    private String readRaw(String path) {
        YamlConfiguration root = YamlUtil.load(new File(plugin.getDataFolder(), "config.yml"));
        String value = root.getString(path, "ALL");
        if (value == null) {
            return "ALL";
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        if ("FALSE".equals(upper) || "OFF".equals(upper) || "NO".equals(upper)
                || "0".equals(upper) || "DISABLE".equals(upper) || "DISABLED".equals(upper)) {
            return "false";
        }
        switch (upper) {
            case "ALL":
            case "LEFT":
            case "RIGHT":
            case "MIDDLE":
                return upper;
            default:
                return "ALL";
        }
    }

    /**
     * 写盘（带引号规则）+ 就地重载 + 丢弃按钮缓存。
     *
     * @param player 触发玩家（失败时提示，可为 null）
     * @param path   config.yml 路径
     * @param value  值
     * @return true 表示成功
     */
    private boolean save(Player player, String path, String value) {
        boolean ok = YamlPatch.put(new File(plugin.getDataFolder(), "config.yml"), path, value);
        return finishSave(player, ok);
    }

    /**
     * 写盘（原样值，不加引号）+ 就地重载 + 丢弃按钮缓存。
     *
     * @param player 触发玩家
     * @param path   路径
     * @param raw    原样文本
     * @return true 表示成功
     */
    private boolean saveRaw(Player player, String path, String raw) {
        boolean ok = YamlPatch.putRaw(new File(plugin.getDataFolder(), "config.yml"), path, raw);
        return finishSave(player, ok);
    }

    /**
     * 写盘成功后统一执行就地重载。
     *
     * @param player 触发玩家
     * @param wrote  是否写盘成功
     * @return true 表示写盘并重载成功
     */
    private boolean finishSave(Player player, boolean wrote) {
        if (!wrote) {
            if (player != null) {
                player.sendMessage(LangUtil.message("reloadFailureMsg"));
            }
            return false;
        }
        try {
            MenuConfig config = configProvider.get();
            config.reload(plugin);
            if (guiManager != null) {
                guiManager.invalidateCache();
            }
            return true;
        } catch (Exception e) {
            if (player != null) {
                player.sendMessage(LangUtil.message("reloadFailureMsg"));
            }
            return false;
        }
    }
}
