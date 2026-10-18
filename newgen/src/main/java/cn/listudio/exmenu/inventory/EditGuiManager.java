package cn.listudio.exmenu.inventory;

import cn.listudio.exmenu.config.MenuConfig;
import cn.listudio.exmenu.constants.InputTypeEnum;
import cn.listudio.exmenu.util.ItemStackUtil;
import cn.listudio.exmenu.util.LangUtil;
import cn.listudio.exmenu.util.SchedulerUtil;
import cn.listudio.exmenu.util.TextUtil;
import cn.listudio.exmenu.util.YamlUtil;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.AnvilGui;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import com.github.stefvanschie.inventoryframework.pane.util.Slot;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 可视化菜单编辑器（{@code /exmenu edit <菜单>}）。
 *
 * <p>面向"看不懂 YAML"的用户：整个菜单结构都在界面里改 ——
 * 点格子加按钮、点按钮进属性页、每项属性一个按钮、文本走铁砧输入框、
 * 改动即时写回 {@code menu/*.yml} 并生效。
 *
 * <p><b>状态机</b>：每个玩家一个 {@link EditState}（步骤 + 上下文）。
 * 每次修改的链路是：异步读盘 → 改内存 → 写盘（首次覆盖前 .bak 备份）→
 * 重载快照 → 丢弃该菜单按钮缓存 → 重开界面。
 * 没有独立的"草稿缓冲"，也就没有"草稿和磁盘不同步"这一整类 bug。
 *
 * <p><b>写盘代价</b>：YAML 经 {@code YamlConfiguration.save()} 重写后注释会丢失，
 * 因此首次覆盖保存前把原文件备份为 {@code 同名.yml.bak}（仅在备份不存在时）。
 *
 * <p><b>图标材质</b>：一律字符串名经 XSeries 解析，不直接引用 {@code Material}
 * 枚举常量 —— 旧服务端（1.13~1.16）没有 LIME_DYE / ENDER_EYE 这些新常量，
 * 枚举引用在旧服务端上会抛 {@code NoSuchFieldError}。
 *
 * <p><b>线程规则</b>：文件 IO 全部异步；界面构建回到实体线程；
 * 会话表用 {@code ConcurrentHashMap}。
 */
public final class EditGuiManager implements Listener {

    /** 会话步骤。 */
    private enum Step {
        /** 主界面（菜单格子视图）。 */
        MAIN,
        /** 单按钮属性页。 */
        BUTTON,
        /** 按钮列表页（槽位溢出时的入口）。 */
        BUTTONS_INDEX,
        /** 通用列表编辑器（lore/commands/permissions 等）。 */
        LIST,
        /** 菜单级 openCommand 列表。 */
        MENU_COMMANDS,
        /** 帮助页。 */
        HELP
    }

    /** 会话模式：编辑中 / 正在预览菜单（预览关闭后回到编辑界面）。 */
    private enum Mode { EDIT, PREVIEW }

    /** 单个玩家的编辑会话。 */
    private static final class EditState {
        /** 菜单文件名（含 .yml）。 */
        String menuFile;
        /** 当前步骤。 */
        Step step = Step.MAIN;
        /** 模式。 */
        Mode mode = Mode.EDIT;
        /** 当前按钮下标（BUTTON/LIST 步骤用）。 */
        int buttonIndex = -1;
        /** 列表编辑器正在编辑的键（commands / lore / actions.left ...）。 */
        String listKey;
        /** 列表编辑器标题。 */
        String listTitle;
        /** 待二次确认删除的按钮下标（-1 表示没有）。 */
        int pendingDelete = -1;
        /** 列表页当前页。 */
        int listPage;
    }

    /** 每页最多编辑的列表行数。 */
    private static final int LIST_PAGE_SIZE = 28;

    /** 铁砧输出槽。 */
    private static final int RESULT_SLOT = 2;

    /** 尺寸轮换序列。 */
    private static final int[] SIZES = {27, 54, 18, 9, 36};

    private final Plugin plugin;
    private final ConfigProvider configProvider;
    private final MenuGuiManager guiManager;
    private final ItemLibraryGuiManager libraryGui;

    /** 物品库 DAO（手持复杂物品入库用）。 */
    private cn.listudio.exmenu.service.MenuItemService itemService;

    /** 玩家 → 编辑会话。 */
    private final Map<UUID, EditState> sessions = new ConcurrentHashMap<>();

    /**
     * 构造。
     *
     * @param plugin         插件实例
     * @param configProvider 配置提供者
     * @param guiManager     菜单界面管理器（预览与缓存失效）
     * @param libraryGui     物品库界面（选取模式）
     */
    public EditGuiManager(Plugin plugin, ConfigProvider configProvider,
                          MenuGuiManager guiManager, ItemLibraryGuiManager libraryGui) {
        this.plugin = plugin;
        this.configProvider = configProvider;
        this.guiManager = guiManager;
        this.libraryGui = libraryGui;
    }

    /**
     * 注入物品库 DAO。
     *
     * @param itemService DAO
     */
    public void setItemService(cn.listudio.exmenu.service.MenuItemService itemService) {
        this.itemService = itemService;
    }

    /**
     * 进入编辑器。
     *
     * @param player   玩家
     * @param menuFile 菜单文件名（含 .yml）
     */
    public void open(Player player, String menuFile) {
        MenuConfig config = configProvider.get();
        if (config == null || config.getMenu(menuFile) == null) {
            LangUtil.send(player, "noMenu");
            return;
        }
        EditState state = new EditState();
        state.menuFile = menuFile.toLowerCase(Locale.ROOT).endsWith(".yml")
                ? menuFile : menuFile + ".yml";
        sessions.put(player.getUniqueId(), state);
        render(player, state);
    }

    /**
     * 玩家退出时清理会话。
     *
     * @param event 事件
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    /**
     * 预览关闭后自动回到编辑界面。
     *
     * @param event 事件
     */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getPlayer();
        EditState state = sessions.get(player.getUniqueId());
        if (state == null || state.mode != Mode.PREVIEW) {
            return;
        }
        state.mode = Mode.EDIT;
        // 关界面事件里直接开新界面会与容器收尾互相干扰，延后两拍
        SchedulerUtil.runTaskLater(plugin, () -> {
            if (player.isOnline() && state.mode == Mode.EDIT) {
                render(player, state);
            }
        }, 2L);
    }

    // ------------------------------------------------------------------
    // 渲染分发
    // ------------------------------------------------------------------

    /**
     * 按当前步骤渲染界面。
     *
     * @param player 玩家
     * @param state  会话
     */
    private void render(Player player, EditState state) {
        MenuConfig config = configProvider.get();
        YamlConfiguration menu = config == null ? null : config.getMenu(state.menuFile);
        if (menu == null) {
            sessions.remove(player.getUniqueId());
            LangUtil.send(player, "noMenu");
            return;
        }
        switch (state.step) {
            case BUTTON:
                renderButtonPage(player, state, menu);
                break;
            case BUTTONS_INDEX:
                renderButtonsIndex(player, state, menu);
                break;
            case LIST:
            case MENU_COMMANDS:
                renderListPage(player, state, menu);
                break;
            case HELP:
                renderHelp(player, state);
                break;
            case MAIN:
            default:
                renderMain(player, state, menu);
                break;
        }
    }

    // ------------------------------------------------------------------
    // 主界面
    // ------------------------------------------------------------------

    /**
     * 主界面：菜单格子的简化预览 + 底部控制行。
     *
     * @param player 玩家
     * @param state  会话
     * @param menu   菜单快照
     */
    private void renderMain(Player player, EditState state, YamlConfiguration menu) {
        int size = menu.getInt("size", 27);
        ChestGui gui = Guis.chest(6, TextUtil.color("&8编辑: &f"
                + TextUtil.stripColor(orDefault(menu.get("title"), state.menuFile))));
        gui.setOnGlobalClick(event -> event.setCancelled(true));
        StaticPane pane = new StaticPane(9, 6);

        // ---- 槽位网格（0..44；菜单超过 45 槽的按钮从"按钮列表"进入）----
        List<Map<String, Object>> buttons = readButtons(menu);
        Map<Integer, Integer> slotToButton = new LinkedHashMap<>();
        for (int i = 0; i < buttons.size(); i++) {
            for (Integer slot : slotListOf(buttons.get(i))) {
                if (slot != null && slot >= 0 && slot < 54) {
                    slotToButton.put(slot, i);
                }
            }
        }

        for (int slot = 0; slot < 45; slot++) {
            Integer buttonIndex = slotToButton.get(slot);
            if (buttonIndex == null) {
                if (slot >= size) {
                    continue;
                }
                ItemStack empty = ItemStackUtil.build("GRAY_STAINED_GLASS_PANE",
                        "&7空槽位 &8点击新增", Collections.emptyList(), false, 0, true, null, null);
                if (empty == null) {
                    continue;
                }
                final int target = slot;
                pane.addItem(new GuiItem(empty, event ->
                        createButton(player, state, target)), Slot.fromIndex(slot));
                continue;
            }
            Map<String, Object> button = buttons.get(buttonIndex);
            ItemStack icon = buttonIcon(button);
            final int index = buttonIndex;
            pane.addItem(new GuiItem(icon, event -> {
                state.buttonIndex = index;
                state.step = Step.BUTTON;
                render(player, state);
            }), Slot.fromIndex(slot));
        }

        // ---- 控制行 ----
        item(pane, 45, "NAME_TAG", "&e改标题",
                Arrays.asList("&7当前: &f" + orDefault(menu.get("title"), "&8无"), "&7点击输入新标题"),
                event -> promptText(player, state, "菜单标题", menu.getString("title", ""),
                        text -> menuSet(player, state, "title", text)));

        item(pane, 46, "CHEST", "&e改大小: &f" + size,
                Collections.singletonList("&7点击轮换 27/54/18/9/36"),
                event -> {
                    int current = size;
                    int next = SIZES[0];
                    for (int i = 0; i < SIZES.length; i++) {
                        if (SIZES[i] == current) {
                            next = SIZES[(i + 1) % SIZES.length];
                            break;
                        }
                    }
                    menuSet(player, state, "size", String.valueOf(next));
                });

        item(pane, 47, "ENDER_EYE", "&a预览菜单",
                Arrays.asList("&7按玩家视角打开真实渲染", "&7关闭预览后自动回到编辑"),
                event -> {
                    state.mode = Mode.PREVIEW;
                    player.closeInventory();
                    guiManager.openAsAdmin(player, state.menuFile);
                });

        item(pane, 48, "CRAFTING_TABLE", "&e按钮列表",
                Arrays.asList("&7按列表查看全部按钮", "&7（超过 45 槽的按钮从这里进）"),
                event -> {
                    state.step = Step.BUTTONS_INDEX;
                    render(player, state);
                });

        item(pane, 49, "REDSTONE", "&e打开权限",
                Arrays.asList("&7当前: &f" + orDefault(menu.get("permission"), "&8无"),
                        "&7空 = 无需权限"),
                event -> promptText(player, state, "菜单权限节点（留空=无）",
                        menu.getString("permission", ""),
                        text -> menuSet(player, state, "permission", text)));

        item(pane, 50, "NOTE_BLOCK", "&e打开音效",
                Arrays.asList("&7当前: &f" + orDefault(menu.get("sound"), "&8无"), "&7留空 = 不播放"),
                event -> promptText(player, state, "打开音效（留空=不播放）",
                        menu.getString("sound", ""),
                        text -> menuSet(player, state, "sound", text)));

        item(pane, 51, "MAP", "&e绑定指令 openCommand",
                Arrays.asList("&7玩家输入 /指令名 即可打开本菜单",
                        "&7当前: &f" + menu.getStringList("openCommand")),
                event -> {
                    state.listKey = "openCommand";
                    state.listTitle = "绑定指令";
                    state.listPage = 0;
                    state.step = Step.MENU_COMMANDS;
                    render(player, state);
                });

        item(pane, 52, "BOOK", "&b帮助", Collections.singletonList("&7怎么用这个编辑器"),
                event -> {
                    state.step = Step.HELP;
                    render(player, state);
                });

        item(pane, 53, "OAK_DOOR", "&c退出编辑", Collections.emptyList(),
                event -> {
                    sessions.remove(player.getUniqueId());
                    player.closeInventory();
                });

        gui.addPane(Slot.fromIndex(0), pane);
        gui.show(player);
    }

    /**
     * 帮助页。
     *
     * @param player 玩家
     * @param state  会话
     */
    private void renderHelp(Player player, EditState state) {
        ChestGui gui = Guis.chest(2, TextUtil.color("&8编辑帮助"));
        gui.setOnGlobalClick(event -> event.setCancelled(true));
        StaticPane pane = new StaticPane(9, 2);
        item(pane, 10, "CHEST", "&e基本操作",
                Arrays.asList("&7灰格子=空槽位，点它新增按钮", "&7点按钮格子=进入属性页",
                        "&7改动即时保存，无需手动保存"), null);
        item(pane, 12, "ANVIL", "&e文本输入",
                Arrays.asList("&7弹出铁砧界面：在输入框打字", "&7点右侧输出格=确认，直接关闭=取消"), null);
        item(pane, 14, "EMERALD", "&e限购/冷却",
                Arrays.asList("&7必须先在属性页设置非零且全局唯一的 id", "&7id 推荐用负数（-1、-2…）"), null);
        item(pane, 16, "LAVA_BUCKET", "&e删除按钮",
                Arrays.asList("&7属性页左下=删除", "&7需要点两次确认"), null);
        item(pane, 22, "ARROW", "&a返回", Collections.emptyList(),
                event -> {
                    state.step = Step.MAIN;
                    render(player, state);
                });
        gui.addPane(Slot.fromIndex(0), pane);
        gui.show(player);
    }

    /**
     * 按钮列表页。
     *
     * @param player 玩家
     * @param state  会话
     * @param menu   快照
     */
    private void renderButtonsIndex(Player player, EditState state, YamlConfiguration menu) {
        List<Map<String, Object>> buttons = readButtons(menu);
        int rows = Math.max(2, Math.min(6, buttons.size() / 9 + 2));
        ChestGui gui = Guis.chest(rows, TextUtil.color("&8按钮列表（" + buttons.size() + " 个）"));
        gui.setOnGlobalClick(event -> event.setCancelled(true));
        StaticPane pane = new StaticPane(9, rows);

        int shown = Math.min(buttons.size(), (rows - 1) * 9);
        for (int i = 0; i < shown; i++) {
            Map<String, Object> button = buttons.get(i);
            ItemStack icon = buttonIcon(button);
            List<String> lore = new ArrayList<>();
            lore.add("&7槽位: &f" + slotListOf(button));
            lore.add("&7id: &f" + orDefault(button.get("id"), "0"));
            lore.add("&7点击编辑");
            ItemStack display = ItemStackUtil.apply(icon,
                    orDefault(button.get("name"), "&7未命名"), lore,
                    false, 0, true, null, null, 1);
            final int index = i;
            pane.addItem(new GuiItem(display == null ? icon : display, event -> {
                state.buttonIndex = index;
                state.step = Step.BUTTON;
                render(player, state);
            }), Slot.fromIndex(i));
        }
        if (buttons.size() > shown) {
            List<String> lore = new ArrayList<>();
            lore.add("&7还有 " + (buttons.size() - shown) + " 个按钮未显示");
            lore.add("&7回主界面在对应槽位编辑");
            ItemStack more = ItemStackUtil.build("BOOK", "&e更多…", lore, false, 0, true, null, null);
            if (more != null) {
                pane.addItem(new GuiItem(more), Slot.fromIndex(shown));
            }
        }

        item(pane, rows * 9 - 5, "ARROW", "&a返回主界面", Collections.emptyList(),
                event -> {
                    state.step = Step.MAIN;
                    render(player, state);
                });

        gui.addPane(Slot.fromIndex(0), pane);
        gui.show(player);
    }

    // ------------------------------------------------------------------
    // 按钮属性页
    // ------------------------------------------------------------------

    /**
     * 单个按钮的属性页。
     *
     * @param player 玩家
     * @param state  会话
     * @param menu   快照
     */
    private void renderButtonPage(Player player, EditState state, YamlConfiguration menu) {
        List<Map<String, Object>> buttons = readButtons(menu);
        if (state.buttonIndex < 0 || state.buttonIndex >= buttons.size()) {
            state.step = Step.MAIN;
            render(player, state);
            return;
        }
        Map<String, Object> button = buttons.get(state.buttonIndex);

        ChestGui gui = Guis.chest(6, TextUtil.color("&8编辑按钮 #&f" + (state.buttonIndex + 1)
                + " &8槽位 &f" + slotListOf(button)));
        gui.setOnGlobalClick(event -> event.setCancelled(true));
        StaticPane pane = new StaticPane(9, 6);

        // ---- 预览 ----
        pane.addItem(new GuiItem(buttonIcon(button)), Slot.fromIndex(4));

        // ---- 图标三件套 ----
        item(pane, 10, "PAINTING", "&e图标: 用手持物品",
                Collections.singletonList("&7把当前主手物品设为图标"),
                event -> applyHeldItem(player, state));
        item(pane, 11, "SLIME_BALL", "&e图标: 输入材质名",
                Collections.singletonList("&7如 DIAMOND、WOOL:14"),
                event -> promptText(player, state, "材质名",
                        orDefault(button.get("material"), "STONE"),
                        text -> {
                            if (ItemStackUtil.parse(text) == null) {
                                player.sendMessage(LangUtil.format("configError", "%reason%",
                                        "无法解析的材质: " + text));
                                render(player, state);
                                return;
                            }
                            mutateButton(player, state, map -> {
                                map.remove("id");
                                map.put("material", text.trim());
                                return true;
                            });
                        }));
        item(pane, 12, "CHEST_MINECART", "&e图标: 从物品库选",
                Arrays.asList("&7浏览已存入的物品库", "&7选中后按钮引用物品库 id"),
                event -> libraryGui.openPicker(player, entry -> mutateButton(player, state, map -> {
                    map.put("id", entry.getId());
                    map.remove("material");
                    return true;
                }), () -> render(player, state)));

        // ---- 文本属性 ----
        item(pane, 13, "NAME_TAG", "&e名称",
                Collections.singletonList("&7当前: &f" + orDefault(button.get("name"), "&8未命名")),
                event -> promptText(player, state, "按钮名称",
                        orDefault(button.get("name"), ""),
                        text -> mutateButton(player, state, map -> {
                            putOrRemove(map, "name", text);
                            return true;
                        })));
        item(pane, 14, "WRITABLE_BOOK", "&e描述 Lore",
                listPreview(button.get("lore"), 3),
                event -> openListEditor(player, state, "lore", "描述 Lore"));

        // ---- 动作链 ----
        item(pane, 15, "COMMAND_BLOCK", "&e动作 commands",
                listPreview(button.get("commands"), 4),
                event -> openListEditor(player, state, "commands", "动作链 commands"));
        item(pane, 16, "IRON_SWORD", "&e左键附加动作",
                listPreview(button.get("actions.left"), 3),
                event -> openListEditor(player, state, "actions.left", "左键附加动作"));
        item(pane, 17, "SHIELD", "&e右键附加动作",
                listPreview(button.get("actions.right"), 3),
                event -> openListEditor(player, state, "actions.right", "右键附加动作"));
        item(pane, 19, "ENDER_CHEST", "&e中键附加动作",
                listPreview(button.get("actions.middle"), 3),
                event -> openListEditor(player, state, "actions.middle", "中键附加动作"));

        // ---- 限制 ----
        item(pane, 20, "CLOCK", "&e冷却 cd",
                Arrays.asList("&7当前: &f" + orDefault(button.get("cd"), "0"),
                        "&7支持 5s / 2m / 1h / 毫秒数，0=无"),
                event -> promptText(player, state, "冷却时长（5s/2m/1h/毫秒）",
                        orDefault(button.get("cd"), ""),
                        text -> mutateButton(player, state, map -> {
                            putOrRemove(map, "cd", text);
                            return true;
                        })));
        item(pane, 21, "PAPER", "&e限购 limit",
                Arrays.asList("&7当前: &f" + orDefault(button.get("limit"), "0"),
                        "&7前提是 id 非零且全局唯一"),
                event -> promptText(player, state, "限购次数（0=无限制）",
                        orDefault(button.get("limit"), "0"),
                        text -> {
                            if (!text.trim().isEmpty() && toInt(text) == null) {
                                player.sendMessage(LangUtil.message("noNumber"));
                                render(player, state);
                                return;
                            }
                            mutateButton(player, state, map -> {
                                putOrRemove(map, "limit", text);
                                return true;
                            });
                        }));
        item(pane, 22, "CHEST", "&e物品库 id",
                Arrays.asList("&7当前: &f" + orDefault(button.get("id"), "0"),
                        "&7负数=限购标识；正数=引用物品库物品", "&7必须全局唯一！"),
                event -> promptText(player, state, "按钮 id（0=不参与限制）",
                        orDefault(button.get("id"), "0"),
                        text -> {
                            if (!text.trim().isEmpty() && toInt(text) == null) {
                                player.sendMessage(LangUtil.message("noNumber"));
                                render(player, state);
                                return;
                            }
                            mutateButton(player, state, map -> {
                                putOrRemove(map, "id", text);
                                return true;
                            });
                        }));

        // ---- 开关 ----
        booleanToggle(pane, player, state, button, 28, "close", "&e点击后关闭菜单");
        booleanToggle(pane, player, state, button, 29, "confirm", "&e二次确认");
        booleanToggle(pane, player, state, button, 30, "limitHide", "&e用完后隐藏");
        booleanToggle(pane, player, state, button, 31, "cdHide", "&e冷却中隐藏");
        booleanToggle(pane, player, state, button, 32, "enchant", "&e附魔光效");

        // ---- 条件与权限 ----
        item(pane, 33, "LADDER", "&e可点条件 conditions",
                listPreview(button.get("conditions"), 3),
                event -> openListEditor(player, state, "conditions", "可点条件"));
        item(pane, 34, "CAULDRON", "&e隐藏条件 conditionsHide",
                listPreview(button.get("conditionsHide"), 3),
                event -> openListEditor(player, state, "conditionsHide", "隐藏条件"));
        item(pane, 35, "TRIPWIRE_HOOK", "&e所需权限 permission",
                listPreview(button.get("permission"), 3),
                event -> openListEditor(player, state, "permission", "所需权限"));
        item(pane, 37, "BARRIER", "&e排斥权限 notPermission",
                listPreview(button.get("notPermission"), 3),
                event -> openListEditor(player, state, "notPermission", "排斥权限"));

        // ---- 输入 ----
        item(pane, 38, "ANVIL", "&e输入提示 input",
                Arrays.asList("&7当前: &f" + orDefault(button.get("input"), "&8无"),
                        "&7非空即先要求玩家输入；可写类型名或提示文本"),
                event -> promptText(player, state, "输入提示（留空=不启用）",
                        orDefault(button.get("input"), ""),
                        text -> mutateButton(player, state, map -> {
                            putOrRemove(map, "input", text);
                            return true;
                        })));
        item(pane, 39, "HOPPER", "&e输入通道 inputMethod",
                Arrays.asList("&7当前: &f" + orDefault(button.get("inputMethod"), "CHAT"),
                        "&7点击切换 CHAT / ANVIL"),
                event -> {
                    String current = orDefault(button.get("inputMethod"), "CHAT")
                            .toUpperCase(Locale.ROOT);
                    String next = "ANVIL".equals(current) ? "" : "ANVIL";
                    mutateButton(player, state, map -> {
                        putOrRemove(map, "inputMethod", next);
                        return true;
                    });
                });
        item(pane, 40, "FEATHER", "&e输入类型",
                Arrays.asList("&7当前: &f" + orDefault(button.get("inputType"), "STRING"),
                        "&7点击轮换 STRING/NUMBER/POSITIVE_NUMBER"),
                event -> {
                    InputTypeEnum current = InputTypeEnum.parse(
                            orDefault(button.get("inputType"), "STRING"));
                    InputTypeEnum[] all = InputTypeEnum.values();
                    InputTypeEnum next = all[(current.ordinal() + 1) % all.length];
                    mutateButton(player, state, map -> {
                        map.put("inputType", next.name());
                        return true;
                    });
                });

        // ---- 数值 ----
        item(pane, 41, "ITEM_FRAME", "&e数量 amount",
                Collections.singletonList("&7当前: &f" + orDefault(button.get("amount"), "1")),
                event -> promptText(player, state, "物品数量（1~64）",
                        orDefault(button.get("amount"), "1"),
                        text -> {
                            Integer value = toInt(text);
                            if (value == null || value < 1 || value > 64) {
                                player.sendMessage(LangUtil.message("noPositiveNumber"));
                                render(player, state);
                                return;
                            }
                            mutateButton(player, state, map -> {
                                map.put("amount", value);
                                return true;
                            });
                        }));
        item(pane, 43, "LODESTONE", "&e槽位 slots",
                Collections.singletonList("&7当前: &f" + slotListOf(button) + "（从 0 开始）"),
                event -> promptText(player, state, "槽位号（0~53）",
                        slotListOf(button).isEmpty() ? "" : String.valueOf(slotListOf(button).get(0)),
                        text -> {
                            Integer value = toInt(text);
                            if (value == null || value < 0 || value > 53) {
                                player.sendMessage(LangUtil.format("configError",
                                        "%reason%", "槽位需在 0~53"));
                                render(player, state);
                                return;
                            }
                            mutateButton(player, state, map -> {
                                map.remove("slots");
                                map.put("slot", value);
                                return true;
                            });
                        }));
        item(pane, 45, "NETHER_STAR", "&e优先级 priority",
                Collections.singletonList("&7同槽位冲突时大者胜；当前: &f"
                        + orDefault(button.get("priority"), "0")),
                event -> promptText(player, state, "优先级（整数）",
                        orDefault(button.get("priority"), "0"),
                        text -> {
                            if (!text.trim().isEmpty() && toInt(text) == null) {
                                player.sendMessage(LangUtil.message("noNumber"));
                                render(player, state);
                                return;
                            }
                            mutateButton(player, state, map -> {
                                putOrRemove(map, "priority", text);
                                return true;
                            });
                        }));

        // ---- 危险区 / 导航 ----
        boolean deleteArmed = state.pendingDelete == state.buttonIndex;
        item(pane, 48, deleteArmed ? "TNT" : "LAVA_BUCKET",
                deleteArmed ? "&c&l再点一次确认删除" : "&c删除按钮",
                Collections.singletonList(deleteArmed ? "&7再次点击将移除这个按钮"
                        : "&7删除需要点击两次确认"),
                event -> {
                    if (state.pendingDelete == state.buttonIndex) {
                        state.pendingDelete = -1;
                        deleteButton(player, state);
                    } else {
                        state.pendingDelete = state.buttonIndex;
                        render(player, state);
                    }
                });

        item(pane, 49, "ARROW", "&a返回主界面", Collections.emptyList(),
                event -> {
                    state.pendingDelete = -1;
                    state.buttonIndex = -1;
                    state.step = Step.MAIN;
                    render(player, state);
                });
        item(pane, 51, "GOLDEN_BOOTS", "&a上一个按钮", Collections.emptyList(),
                event -> {
                    if (state.buttonIndex > 0) {
                        state.buttonIndex--;
                    }
                    state.pendingDelete = -1;
                    render(player, state);
                });
        item(pane, 53, "GOLDEN_BOOTS", "&a下一个按钮", Collections.emptyList(),
                event -> {
                    if (state.buttonIndex < buttons.size() - 1) {
                        state.buttonIndex++;
                    }
                    state.pendingDelete = -1;
                    render(player, state);
                });

        gui.addPane(Slot.fromIndex(0), pane);
        gui.show(player);
    }

    /**
     * 布尔开关按钮（读写按钮 map 上的键）。
     *
     * @param pane   面板
     * @param player 玩家
     * @param state  会话
     * @param button 当前按钮 map
     * @param slot   槽位
     * @param key    配置键
     * @param name   名称
     */
    private void booleanToggle(StaticPane pane, Player player, EditState state,
                               Map<String, Object> button, int slot, String key, String name) {
        boolean on = toBoolean(button.get(key), false);
        item(pane, slot, on ? "LIME_DYE" : "REDSTONE_BLOCK",
                name, Collections.singletonList("&7当前: " + (on ? "&a开" : "&c关") + "，点击切换"),
                event -> mutateButton(player, state, map -> {
                    map.put(key, !on);
                    return true;
                }));
    }

    // ------------------------------------------------------------------
    // 通用列表编辑器
    // ------------------------------------------------------------------

    /**
     * 打开列表编辑器。
     *
     * @param player 玩家
     * @param state  会话
     * @param key    列表键（支持 actions.left 这类路径）
     * @param title  标题
     */
    private void openListEditor(Player player, EditState state, String key, String title) {
        state.listKey = key;
        state.listTitle = title;
        state.listPage = 0;
        state.step = Step.LIST;
        render(player, state);
    }

    /**
     * 渲染列表编辑器页。
     *
     * @param player 玩家
     * @param state  会话
     * @param menu   快照
     */
    private void renderListPage(Player player, EditState state, YamlConfiguration menu) {
        List<String> lines;
        if (state.step == Step.MENU_COMMANDS) {
            lines = menu.getStringList("openCommand");
        } else {
            List<Map<String, Object>> buttons = readButtons(menu);
            if (state.buttonIndex < 0 || state.buttonIndex >= buttons.size()) {
                state.step = Step.MAIN;
                render(player, state);
                return;
            }
            lines = stringListOf(buttons.get(state.buttonIndex).get(state.listKey));
        }

        int pages = Math.max(1, (lines.size() + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE);
        int page = Math.min(state.listPage, pages - 1);

        ChestGui gui = Guis.chest(4, TextUtil.color("&8" + state.listTitle
                + " &7(" + (page + 1) + "/" + pages + ")"));
        gui.setOnGlobalClick(event -> event.setCancelled(true));
        StaticPane pane = new StaticPane(9, 4);

        int from = page * LIST_PAGE_SIZE;
        for (int i = 0; i < LIST_PAGE_SIZE; i++) {
            int lineIndex = from + i;
            if (lineIndex >= lines.size()) {
                break;
            }
            String line = lines.get(lineIndex);
            ItemStack item = ItemStackUtil.build("PAPER",
                    "&7#" + (lineIndex + 1) + " &f" + abbreviate(line, 46),
                    Arrays.asList("&7点击编辑这一行", "&7Shift+点击删除这一行"),
                    false, 0, true, null, null);
            if (item == null) {
                continue;
            }
            pane.addItem(new GuiItem(item, event -> {
                if (event.isShiftClick()) {
                    removeLine(player, state, lineIndex);
                } else {
                    editLine(player, state, lineIndex, line);
                }
            }), Slot.fromIndex(i));
        }

        String addDefault = isActionList(state.listKey) ? "[message] " : "";
        item(pane, 28, "GREEN_DYE", "&a新增一行",
                isActionList(state.listKey)
                        ? Arrays.asList("&7动作需以标签开头",
                        "&7如 [message] [console] [open] [delay] [sound]")
                        : Collections.singletonList("&7追加一个空行再编辑"),
                event -> editLine(player, state, lines.size(), addDefault));

        item(pane, 31, "ARROW", "&a返回", Collections.emptyList(),
                event -> {
                    state.step = state.buttonIndex >= 0 ? Step.BUTTON : Step.MAIN;
                    render(player, state);
                });
        if (page > 0) {
            item(pane, 33, "GOLDEN_BOOTS", "&e上一页", Collections.emptyList(),
                    event -> {
                        state.listPage = page - 1;
                        render(player, state);
                    });
        }
        if (page < pages - 1) {
            item(pane, 35, "GOLDEN_BOOTS", "&e下一页", Collections.emptyList(),
                    event -> {
                        state.listPage = page + 1;
                        render(player, state);
                    });
        }

        gui.addPane(Slot.fromIndex(0), pane);
        gui.show(player);
    }

    /**
     * 是否是动作链类列表（需要标签前缀）。
     *
     * @param key 列表键
     * @return true 表示 commands / actions.xxx
     */
    private static boolean isActionList(String key) {
        return "commands".equals(key) || (key != null && key.startsWith("actions."));
    }

    /**
     * 编辑一行（铁砧输入后写回列表）。
     *
     * @param player    玩家
     * @param state     会话
     * @param lineIndex 行下标（等于当前行数时为新增）
     * @param current   当前文本
     */
    private void editLine(Player player, EditState state, int lineIndex, String current) {
        promptText(player, state, state.listTitle + " #" + (lineIndex + 1),
                current, text -> applyLine(player, state, lineIndex, text));
    }

    /**
     * 把一行文本写回列表。
     *
     * @param player    玩家
     * @param state     会话
     * @param lineIndex 行下标
     * @param text      新文本
     */
    private void applyLine(Player player, EditState state, int lineIndex, String text) {
        if (state.step == Step.MENU_COMMANDS) {
            mutateMenu(player, state, y -> {
                List<String> list = new ArrayList<>(y.getStringList("openCommand"));
                if (lineIndex >= list.size()) {
                    if (!text.trim().isEmpty()) {
                        list.add(text.trim());
                    }
                } else if (text.trim().isEmpty()) {
                    list.remove(lineIndex);
                } else {
                    list.set(lineIndex, text.trim());
                }
                y.set("openCommand", list);
                return true;
            });
            return;
        }
        mutateButton(player, state, map -> {
            List<String> list = stringListOf(map.get(state.listKey));
            if (lineIndex >= list.size()) {
                if (!text.trim().isEmpty()) {
                    list.add(text);
                }
            } else if (text.trim().isEmpty()) {
                list.remove(lineIndex);
            } else {
                list.set(lineIndex, text);
            }
            putListOrRemove(map, state.listKey, list);
            return true;
        });
    }

    /**
     * 删除列表中的一行。
     *
     * @param player    玩家
     * @param state     会话
     * @param lineIndex 行下标
     */
    private void removeLine(Player player, EditState state, int lineIndex) {
        if (state.step == Step.MENU_COMMANDS) {
            mutateMenu(player, state, y -> {
                List<String> list = new ArrayList<>(y.getStringList("openCommand"));
                if (lineIndex < list.size()) {
                    list.remove(lineIndex);
                }
                y.set("openCommand", list);
                return true;
            });
            return;
        }
        mutateButton(player, state, map -> {
            List<String> list = stringListOf(map.get(state.listKey));
            if (lineIndex < list.size()) {
                list.remove(lineIndex);
            }
            putListOrRemove(map, state.listKey, list);
            return true;
        });
    }

    // ------------------------------------------------------------------
    // 写盘核心
    // ------------------------------------------------------------------

    /**
     * 新增按钮。
     *
     * @param player 玩家
     * @param state  会话
     * @param slot   槽位
     */
    private void createButton(Player player, EditState state, int slot) {
        mutateMenu(player, state, y -> {
            List<Map<String, Object>> buttons = readButtons(y);
            Map<String, Object> button = new LinkedHashMap<>();
            button.put("slot", slot);
            MenuConfig config = configProvider.get();
            if (config == null || config.isAutoCreateId()) {
                // autoCreateId：找一个未占用的最小负数 id。
                // 负数 id 是"纯限制标识"，不与物品库正数 id 冲突，
                // 让 cd/limit 一配上就有效，不用新手再理解 id 语义。
                java.util.Set<Integer> usedNegative = new java.util.HashSet<>();
                for (Map<String, Object> existing : buttons) {
                    Integer id = toInt(existing.get("id"));
                    if (id != null && id < 0) {
                        usedNegative.add(id);
                    }
                }
                int free = -1;
                while (usedNegative.contains(free)) {
                    free--;
                }
                button.put("id", free);
            }
            button.put("material", "STONE");
            button.put("name", "&e新按钮");
            buttons.add(button);
            y.set("buttons", new ArrayList<>(buttons));
            state.buttonIndex = buttons.size() - 1;
            state.step = Step.BUTTON;
            return true;
        });
    }

    /**
     * 删除当前按钮。
     *
     * @param player 玩家
     * @param state  会话
     */
    private void deleteButton(Player player, EditState state) {
        final int index = state.buttonIndex;
        mutateMenu(player, state, y -> {
            List<Map<String, Object>> buttons = readButtons(y);
            if (index < 0 || index >= buttons.size()) {
                return false;
            }
            buttons.remove(index);
            y.set("buttons", new ArrayList<>(buttons));
            state.buttonIndex = -1;
            state.step = Step.MAIN;
            return true;
        });
    }

    /**
     * 用主手物品作为图标。
     *
     * <p>简单物品（无自定义名称/Lore）直接写材质名；
     * 复杂物品存入物品库并让按钮引用物品库 id —— 否则 meta 细节会丢。
     *
     * @param player 玩家
     * @param state  会话
     */
    private void applyHeldItem(Player player, EditState state) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            player.sendMessage(LangUtil.message("noItem"));
            return;
        }
        boolean plain = true;
        if (hand.hasItemMeta()) {
            org.bukkit.inventory.meta.ItemMeta meta = hand.getItemMeta();
            plain = meta == null || (!meta.hasDisplayName() && !meta.hasLore());
        }
        if (plain) {
            final String materialName = hand.getType().name();
            mutateButton(player, state, map -> {
                map.remove("id");
                map.put("material", materialName);
                return true;
            });
            return;
        }
        String json = ItemStackUtil.serialize(hand);
        if (json == null || itemService == null) {
            player.sendMessage(LangUtil.message("noItem"));
            return;
        }
        String md5 = md5(json);
        SchedulerUtil.runTaskAsynchronously(plugin, () -> {
            Integer existing = itemService.findIdByMd5(md5);
            final Integer id = existing != null ? existing
                    : itemService.save(new cn.listudio.exmenu.enter.MenuItem(
                            null, md5, json, System.currentTimeMillis()));
            if (id == null) {
                SchedulerUtil.runAtEntity(plugin, player, () ->
                        player.sendMessage(LangUtil.message("clickOperatorReason")));
                return;
            }
            mutateButton(player, state, map -> {
                map.put("id", id);
                map.remove("material");
                return true;
            });
        });
    }

    /**
     * 计算 MD5。
     *
     * @param text 文本
     * @return 十六进制摘要
     */
    private static String md5(String text) {
        try {
            java.security.MessageDigest digest =
                    java.security.MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    /**
     * 修改单个按钮并保存。
     *
     * @param player  玩家
     * @param state   会话
     * @param mutator 修改器（在异步线程对按钮 map 操作）
     */
    private void mutateButton(Player player, EditState state,
                              Predicate<Map<String, Object>> mutator) {
        mutateMenu(player, state, y -> {
            List<Map<String, Object>> buttons = readButtons(y);
            if (state.buttonIndex < 0 || state.buttonIndex >= buttons.size()) {
                return false;
            }
            Map<String, Object> button = buttons.get(state.buttonIndex);
            if (!mutator.test(button)) {
                return false;
            }
            y.set("buttons", new ArrayList<>(buttons));
            return true;
        });
    }

    /**
     * 修改菜单级标量键并保存。
     *
     * @param player 玩家
     * @param state  会话
     * @param key    键
     * @param value  值文本（size 等数字键自动转型；空串=移除）
     */
    private void menuSet(Player player, EditState state, String key, String value) {
        mutateMenu(player, state, y -> {
            if (value == null || value.trim().isEmpty()) {
                y.set(key, null);
                return true;
            }
            String text = value.trim();
            if ("size".equals(key)) {
                Integer size = toInt(text);
                if (size == null) {
                    return false;
                }
                y.set(key, size);
                return true;
            }
            y.set(key, text);
            return true;
        });
    }

    /**
     * 修改菜单并写盘：异步读盘 → 修改 → 写盘（首次覆盖前 .bak 备份）→ 重载快照 → 丢缓存 → 重开界面。
     *
     * @param player  玩家
     * @param state   会话
     * @param mutator 修改器，返回 false 表示放弃本次修改
     */
    private void mutateMenu(Player player, EditState state, Predicate<YamlConfiguration> mutator) {
        final String menuFile = state.menuFile;
        SchedulerUtil.runTaskAsynchronously(plugin, () -> {
            File file = new File(new File(plugin.getDataFolder(), "menu"), menuFile);
            if (!file.exists()) {
                SchedulerUtil.runAtEntity(plugin, player,
                        () -> LangUtil.send(player, "noMenu"));
                return;
            }
            YamlConfiguration y = YamlUtil.load(file);
            boolean changed;
            try {
                changed = mutator.test(y);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("编辑操作失败: " + e.getMessage());
                changed = false;
            }
            if (!changed) {
                SchedulerUtil.runAtEntity(plugin, player, () -> render(player, state));
                return;
            }
            // 首次覆盖保存且原文件含注释 → 备份一次
            try {
                String head = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                if (head.indexOf('#') >= 0) {
                    File bak = new File(file.getParentFile(), menuFile + ".bak");
                    if (!bak.exists()) {
                        java.nio.file.Files.copy(file.toPath(), bak.toPath());
                    }
                }
            } catch (java.io.IOException ignored) {
                // 备份失败不阻断保存：最坏情况是失去注释
            }
            if (!YamlUtil.save(y, file)) {
                SchedulerUtil.runAtEntity(plugin, player, () ->
                        player.sendMessage(LangUtil.message("reloadFailureMsg")));
                return;
            }
            MenuConfig config = configProvider.get();
            if (config != null) {
                config.loadMenus();
            }
            guiManager.invalidateCache(menuFile);
            SchedulerUtil.runAtEntity(plugin, player, () -> {
                if (player.isOnline()) {
                    render(player, state);
                }
            });
        });
    }

    // ------------------------------------------------------------------
    // 铁砧文本输入（编辑器专用，完成后回到当前编辑页）
    // ------------------------------------------------------------------

    /**
     * 弹出铁砧输入框。
     *
     * @param player   玩家
     * @param state    会话
     * @param prompt   标题
     * @param current  默认文本
     * @param onSubmit 提交回调（传原始文本，可为空串表示"清除"）
     */
    private void promptText(Player player, EditState state, String prompt,
                            String current, Consumer<String> onSubmit) {
        AnvilGui gui = Guis.anvil(TextUtil.color("&8" + prompt), plugin);
        ItemStack base = ItemStackUtil.build("PAPER",
                "&e点右侧输出格确认",
                Collections.singletonList("&7当前: " + abbreviate(orDefault(current, "&8空"), 30)
                        + "；直接关闭 = 取消"),
                false, 0, true, null, null);
        if (base != null) {
            gui.getFirstItemComponent().setItem(new GuiItem(base), Slot.fromIndex(0));
        }
        AtomicReference<String> typed = new AtomicReference<>(current == null ? "" : current);
        AtomicBoolean done = new AtomicBoolean(false);
        gui.setOnNameInputChanged(t -> typed.set(t == null ? "" : t));
        gui.setOnGlobalClick(event -> {
            event.setCancelled(true);
            if (event.getClickedInventory() == null
                    || !event.getClickedInventory().equals(gui.getInventory())
                    || event.getRawSlot() != RESULT_SLOT) {
                return;
            }
            done.set(true);
            player.closeInventory();
            onSubmit.accept(typed.get());
        });
        gui.setOnClose(event -> {
            if (done.get()) {
                return;
            }
            done.set(true);
            SchedulerUtil.runAtEntity(plugin, player, () -> {
                if (player.isOnline()) {
                    render(player, state);
                }
            });
        });
        gui.show(player);
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /**
     * 读取 buttons 列表（每项转为可写的 LinkedHashMap）。
     *
     * @param y 菜单配置
     * @return 按钮列表
     */
    private static List<Map<String, Object>> readButtons(YamlConfiguration y) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<?, ?> raw : y.getMapList("buttons")) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            result.add(copy);
        }
        return result;
    }

    /**
     * 按钮的槽位列表（兼容 slot / slots 两种写法）。
     *
     * @param button 按钮 map
     * @return 槽位列表
     */
    private static List<Integer> slotListOf(Map<String, Object> button) {
        List<Integer> slots = new ArrayList<>();
        Object raw = button.get("slots");
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                Integer slot = toInt(item);
                if (slot != null) {
                    slots.add(slot);
                }
            }
            return slots;
        }
        Integer slot = toInt(button.get("slot"));
        if (slot != null) {
            slots.add(slot);
        }
        return slots;
    }

    /**
     * 把按钮 map 的图标渲染出来（编辑器简化版：只按材质，不查物品库、不跑 PAPI）。
     *
     * @param button 按钮 map
     * @return 图标物品，材质无效时按 id 引用或 STONE
     */
    private static ItemStack buttonIcon(Map<String, Object> button) {
        String material = orDefault(button.get("material"), "");
        if (!material.isEmpty()) {
            ItemStack icon = ItemStackUtil.parse(material);
            if (icon != null && icon.getType() != Material.AIR) {
                return icon;
            }
        }
        // 物品库引用的按钮没有 material —— 用箱子矿车提示"来自物品库"
        Integer id = toInt(button.get("id"));
        if (id != null && id > 0) {
            ItemStack marker = ItemStackUtil.parse("CHEST_MINECART");
            return marker == null ? new ItemStack(Material.STONE) : marker;
        }
        return new ItemStack(Material.STONE);
    }

    /**
     * 列表值的预览行。
     *
     * @param raw     原始值
     * @param maxShow 最多预览几行
     * @return 界面 lore
     */
    private static List<String> listPreview(Object raw, int maxShow) {
        List<String> lines = stringListOf(raw);
        List<String> preview = new ArrayList<>();
        preview.add("&7行数: &f" + lines.size());
        for (int i = 0; i < Math.min(maxShow, lines.size()); i++) {
            preview.add("&8" + abbreviate(lines.get(i), 40));
        }
        if (lines.size() > maxShow) {
            preview.add("&8…");
        }
        return preview;
    }

    /**
     * 兼容"单字符串"与"列表"两种 YAML 写法，取字符串列表。
     *
     * @param raw 原始值
     * @return 字符串列表，永不返回 null
     */
    private static List<String> stringListOf(Object raw) {
        List<String> result = new ArrayList<>();
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                result.add(item == null ? "" : String.valueOf(item));
            }
        } else if (raw != null && !String.valueOf(raw).isEmpty()) {
            result.add(String.valueOf(raw));
        }
        return result;
    }

    /**
     * 写回列表键；空列表时移除键（保持 YAML 干净）。
     *
     * <p>actions.left 这类点路径要落成嵌套 map：{@code actions: {left: [...]}}。
     *
     * @param map   按钮 map
     * @param key   键（可能带点）
     * @param lines 新内容
     */
    private static void putListOrRemove(Map<String, Object> map, String key, List<String> lines) {
        if (key == null) {
            return;
        }
        if (key.indexOf('.') < 0) {
            if (lines.isEmpty()) {
                map.remove(key);
            } else {
                map.put(key, new ArrayList<>(lines));
            }
            return;
        }
        String head = key.substring(0, key.indexOf('.'));
        String tail = key.substring(key.indexOf('.') + 1);
        Object childRaw = map.get(head);
        Map<String, Object> child = new LinkedHashMap<>();
        if (childRaw instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) childRaw).entrySet()) {
                child.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        if (lines.isEmpty()) {
            child.remove(tail);
        } else {
            child.put(tail, new ArrayList<>(lines));
        }
        if (child.isEmpty()) {
            map.remove(head);
        } else {
            map.put(head, child);
        }
    }

    /**
     * 值转 Integer。
     *
     * @param raw 原始值
     * @return 数字，失败 null
     */
    private static Integer toInt(Object raw) {
        if (raw instanceof Number) {
            return ((Number) raw).intValue();
        }
        if (raw == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 值转布尔（缺省值兜底）。
     *
     * @param raw          原始值
     * @param defaultValue 缺省
     * @return 布尔
     */
    private static boolean toBoolean(Object raw, boolean defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Boolean) {
            return (Boolean) raw;
        }
        return Boolean.parseBoolean(String.valueOf(raw));
    }

    /**
     * 值写入 map；空串表示移除。纯数字文本存为 Integer（YAML 不带引号）。
     *
     * @param map   按钮 map
     * @param key   键
     * @param value 值
     */
    private static void putOrRemove(Map<String, Object> map, String key, String value) {
        if (value == null || value.trim().isEmpty()) {
            map.remove(key);
            return;
        }
        String text = value.trim();
        if (isNumericText(text)) {
            map.put(key, toInt(text));
            return;
        }
        map.put(key, text);
    }

    /**
     * 是否纯整数文本。
     *
     * @param text 文本
     * @return true 表示是
     */
    private static boolean isNumericText(String text) {
        if (text.isEmpty()) {
            return false;
        }
        int start = text.charAt(0) == '-' ? 1 : 0;
        if (start >= text.length()) {
            return false;
        }
        for (int i = start; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * map/配置值转显示文本。
     *
     * @param value        值
     * @param defaultValue 缺省
     * @return 文本
     */
    private static String orDefault(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? defaultValue : text;
    }

    /**
     * 截断长文本。
     *
     * @param text   文本
     * @param maxLen 最大长度
     * @return 截断结果
     */
    private static String abbreviate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "…";
    }

    /**
     * 通用按钮添加（材质用字符串名，经 XSeries 跨版本解析）。
     *
     * @param pane     面板
     * @param slot     槽位
     * @param material 材质名
     * @param name     名称（含 &amp; 色码）
     * @param lore     说明（含 &amp; 色码）
     * @param onClick  点击回调，null 表示纯展示
     */
    private void item(StaticPane pane, int slot, String material, String name,
                      List<String> lore,
                      Consumer<org.bukkit.event.inventory.InventoryClickEvent> onClick) {
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
}
