package cn.listudio.exmenu.inventory;

import cn.listudio.exmenu.config.MenuConfig;
import cn.listudio.exmenu.enter.MenuItem;
import cn.listudio.exmenu.service.MenuItemService;
import cn.listudio.exmenu.util.ItemStackUtil;
import cn.listudio.exmenu.util.LangUtil;
import cn.listudio.exmenu.util.SchedulerUtil;
import cn.listudio.exmenu.util.TextUtil;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import com.github.stefvanschie.inventoryframework.pane.util.Slot;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 物品库浏览界面（{@code gui/item.yml} 的消费方）。
 *
 * <p>此前 {@code gui/item.yml} 被加载但没有任何入口 —— 本类把它接上：
 * {@code /exmenu library} 打开浏览模式，可视化编辑器打开"选取物品库物品"模式。
 *
 * <p>两种模式的区别只在点击物品格的行为：
 * <ul>
 *   <li><b>浏览</b>：左键取出 1 个，Shift+左键取出一整组</li>
 *   <li><b>选取</b>：把条目交回给调用方（编辑器），并回到编辑器界面</li>
 * </ul>
 *
 * <p>线程模型：{@code findAll} 是阻塞查询，先异步执行；
 * 界面构建与展示回到实体线程（IFramework 非线程安全）。
 */
public final class ItemLibraryGuiManager {

    private final Plugin plugin;
    private final ConfigProvider configProvider;
    private final MenuItemService itemService;

    /**
     * 构造。
     *
     * @param plugin         插件实例
     * @param configProvider 配置提供者
     * @param itemService    物品库 DAO
     */
    public ItemLibraryGuiManager(Plugin plugin, ConfigProvider configProvider,
                                 MenuItemService itemService) {
        this.plugin = plugin;
        this.configProvider = configProvider;
        this.itemService = itemService;
    }

    /**
     * 以浏览模式打开物品库。
     *
     * @param player 玩家
     */
    public void openBrowse(Player player) {
        loadAsyncThenShow(player, 0, null, null);
    }

    /**
     * 以选取模式打开物品库：点击某个条目后回调 {@code onSelect} 并结束。
     *
     * @param player   玩家
     * @param onSelect 选中回调（在实体线程调用）
     */
    public void openPicker(Player player, Consumer<MenuItem> onSelect) {
        loadAsyncThenShow(player, 0, onSelect, null);
    }

    /**
     * 以选取模式打开物品库（带"直接关界面 = 取消"回调）。
     *
     * @param player   玩家
     * @param onSelect 选中回调（在实体线程调用）
     * @param onCancel 玩家未选择直接关闭界面时的回调（在调度线程调用）
     */
    public void openPicker(Player player, Consumer<MenuItem> onSelect, Runnable onCancel) {
        loadAsyncThenShow(player, 0, onSelect, onCancel);
    }

    /**
     * 异步取数后展示指定页。
     *
     * @param player   玩家
     * @param page     页码（从 0 开始）
     * @param onSelect 选取回调；null 表示浏览模式
     * @param onCancel 取消/关闭回调（选取模式下玩家按 ESC 关闭时通知编辑器回到原界面）
     */
    private void loadAsyncThenShow(Player player, int page,
                                   Consumer<MenuItem> onSelect, Runnable onCancel) {
        SchedulerUtil.runTaskAsynchronously(plugin, () -> {
            final List<MenuItem> all = itemService.findAll();
            SchedulerUtil.runAtEntity(plugin, player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                showPage(player, all, page, onSelect, onCancel);
            });
        });
    }

    /**
     * 构建并展示一页。
     *
     * @param player   玩家
     * @param all      全部条目（异步阶段已取回）
     * @param page     页码
     * @param onSelect 选取回调；null 表示浏览模式
     * @param onCancel 取消回调
     */
    private void showPage(Player player, List<MenuItem> all, int page,
                          Consumer<MenuItem> onSelect, Runnable onCancel) {
        MenuConfig config = configProvider.get();
        YamlConfiguration guiConfig = config == null ? null : config.getItemGuiConfig();
        if (guiConfig == null) {
            guiConfig = new YamlConfiguration();
        }

        int size = normalizeSize(guiConfig.getInt("size", 54));
        int rows = size / 9;
        // 约定：最后一行是控制行（previous/next/close/pane），其余为展示区
        final int perPage = Math.max(9, size - 9);
        int maxPage = Math.max(0, (all.size() - 1) / perPage);
        int current = Math.min(Math.max(0, page), maxPage);

        String baseTitle = TextUtil.color(guiConfig.getString("title", "&8物品库"));
        String title = baseTitle + " §7(" + (current + 1) + "/" + (maxPage + 1) + ")";

        ChestGui gui = Guis.chest(rows, title);
        gui.setOnGlobalClick(event -> event.setCancelled(true));
        // select/cancel：选取模式下"选中"与"取消"两条路互斥，用 consumed 标志区分
        // "玩家自己关掉界面"与"选中后调用方主动切换界面"两种关法
        final java.util.concurrent.atomic.AtomicBoolean consumed =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final Consumer<MenuItem> select;
        if (onSelect != null) {
            final Consumer<MenuItem> baseOnSelect = onSelect;
            select = entry -> {
                consumed.set(true);
                baseOnSelect.accept(entry);
            };
            if (onCancel != null) {
                gui.setOnClose(event -> {
                    if (consumed.get()) {
                        return;
                    }
                    SchedulerUtil.runTask(plugin, onCancel);
                });
            }
        } else {
            select = null;
        }

        StaticPane pane = new StaticPane(9, rows);

        // ---- 物品条目：槽位 0 起顺序铺 ----
        int from = current * perPage;
        int to = Math.min(all.size(), from + perPage);
        List<MenuItem> pageItems = new ArrayList<>(all.subList(from, to));
        for (int i = 0; i < pageItems.size(); i++) {
            final MenuItem entry = pageItems.get(i);
            ItemStack item = ItemStackUtil.deserialize(entry.getItemJson());
            if (item == null) {
                // 反序列化失败（跨版本物品被移除）：用灰色玻璃板占位而不是留空槽，
                // 否则后面的条目会整体前移，页码与印象对不上
                item = ItemStackUtil.parse("BARRIER");
                if (item == null) {
                    continue;
                }
            }
            ItemStack display = applyEntrySkin(item, guiConfig, entry);
            pane.addItem(new GuiItem(display, event -> onEntryClick(
                    event.getClick(), player, entry, select, all, current, onCancel)),
                    Slot.fromIndex(i));
        }

        // ---- 翻页 / 关闭 / 装饰板 ----
        if (current > 0) {
            addButton(pane, guiConfig, "previous", () ->
                    loadAsyncThenShow(player, current - 1, select, onCancel));
        }
        if (current < maxPage) {
            addButton(pane, guiConfig, "next", () ->
                    loadAsyncThenShow(player, current + 1, select, onCancel));
        }
        addButton(pane, guiConfig, "close", player::closeInventory);
        addPaneFiller(pane, guiConfig, size);

        gui.addPane(Slot.fromIndex(0), pane);
        gui.show(player);
    }

    /**
     * 点击一个物品条目。
     *
     * @param click    点击类型
     * @param player   玩家
     * @param entry    条目
     * @param onSelect 选取回调；null 表示浏览模式
     * @param all      全部条目（用于取出后刷新页码显示）
     * @param page     当前页
     * @param onCancel 取消回调
     */
    private void onEntryClick(ClickType click, Player player, MenuItem entry,
                              Consumer<MenuItem> onSelect, List<MenuItem> all,
                              int page, Runnable onCancel) {
        if (onSelect != null) {
            onSelect.accept(entry);
            return;
        }
        ItemStack item = ItemStackUtil.deserialize(entry.getItemJson());
        if (item == null) {
            LangUtil.send(player, "noItem");
            return;
        }
        int amount = click == ClickType.SHIFT_LEFT ? item.getMaxStackSize() : 1;
        ItemStackUtil.give(player, item, amount);
        player.sendMessage(LangUtil.message("getMenuItemMsg"));
    }

    /**
     * 按 {@code item.name}/{@code item.lore} 配置覆盖条目的显示名与描述。
     *
     * @param item      条目物品
     * @param guiConfig 界面配置
     * @param entry     条目数据
     * @return 处理后的物品
     */
    private ItemStack applyEntrySkin(ItemStack item, YamlConfiguration guiConfig, MenuItem entry) {
        String materialName = item.getType().name();
        String name = guiConfig.getString("item.name", "&e#%id%")
                .replace("%id%", String.valueOf(entry.getId()))
                .replace("%material%", materialName);
        List<String> lore = new ArrayList<>();
        for (String line : guiConfig.getStringList("item.lore")) {
            lore.add(line.replace("%id%", String.valueOf(entry.getId()))
                    .replace("%material%", materialName));
        }
        return ItemStackUtil.apply(item, name, lore.isEmpty() ? null : lore,
                false, 0, true, null, null, item.getAmount());
    }

    /**
     * 添加一个配置驱动的按钮。
     *
     * @param pane      面板
     * @param guiConfig 界面配置
     * @param key       按钮键
     * @param onClick   点击回调
     */
    private void addButton(StaticPane pane, YamlConfiguration guiConfig, String key,
                           Runnable onClick) {
        String material = guiConfig.getString(key + ".material", "");
        if (TextUtil.isEmpty(material)) {
            return;
        }
        ItemStack item = ItemStackUtil.build(material,
                guiConfig.getString(key + ".name", ""),
                guiConfig.getStringList(key + ".lore"),
                guiConfig.getBoolean(key + ".enchant", false),
                0, true, null, null);
        if (item == null) {
            return;
        }
        int slot = guiConfig.getInt(key + ".slot", -1);
        if (slot < 0) {
            return;
        }
        pane.addItem(new GuiItem(item, event -> onClick.run()), Slot.fromIndex(slot));
    }

    /**
     * 铺装饰板。
     *
     * @param pane      面板
     * @param guiConfig 界面配置
     * @param size      界面大小
     */
    private void addPaneFiller(StaticPane pane, YamlConfiguration guiConfig, int size) {
        String material = guiConfig.getString("pane.material", "");
        if (TextUtil.isEmpty(material)) {
            return;
        }
        ItemStack filler = ItemStackUtil.build(material,
                guiConfig.getString("pane.name", " "),
                guiConfig.getStringList("pane.lore"),
                guiConfig.getBoolean("pane.enchant", false),
                0, true, null, null);
        if (filler == null) {
            return;
        }
        GuiItem guiItem = new GuiItem(filler, event -> event.setCancelled(true));
        for (Integer slot : guiConfig.getIntegerList("pane.slots")) {
            if (slot != null && slot >= 0 && slot < size) {
                pane.addItem(guiItem, Slot.fromIndex(slot));
            }
        }
    }

    /**
     * 规整界面大小。
     *
     * @param size 原始值
     * @return 9~54 且为 9 的倍数
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
}
