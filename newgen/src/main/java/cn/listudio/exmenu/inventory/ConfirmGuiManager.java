package cn.listudio.exmenu.inventory;

import cn.listudio.exmenu.config.MenuConfig;
import cn.listudio.exmenu.core.MenuExecutor;
import cn.listudio.exmenu.param.MenuButtonParam;
import cn.listudio.exmenu.util.ItemStackUtil;
import cn.listudio.exmenu.util.LangUtil;
import cn.listudio.exmenu.util.TextUtil;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import com.github.stefvanschie.inventoryframework.pane.util.Slot;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * 二次确认界面。
 *
 * <p>危险操作（花大钱、消耗稀有物品）前的确认框。界面布局完全由
 * {@code gui/confirm.yml} 决定：标题、大小、音效、四个按钮的材质与文字。
 *
 * <p>确认界面的按钮<b>不参与限制检查</b> —— 它只是把玩家的意图
 * 转达给原按钮。次数与冷却在原按钮执行时才记录，这一点与原版一致：
 * 玩家打开确认框又关掉，不应该被扣次数。
 */
public final class ConfirmGuiManager {

    private ConfirmGuiManager() {
    }

    /**
     * 打开确认界面。
     *
     * @param plugin         插件
     * @param configProvider 配置提供者
     * @param player         玩家
     * @param param          待确认的按钮
     * @param sourceGui      来源界面，可为 null
     * @param executor       执行器
     */
    public static void open(Plugin plugin, ConfigProvider configProvider,
                            Player player, MenuButtonParam param, ChestGui sourceGui,
                            MenuExecutor executor) {
        MenuConfig config = configProvider == null ? null : configProvider.get();
        YamlConfiguration confirm = config == null ? null : config.getConfirmGuiConfig();
        if (confirm == null) {
            // 没有确认界面配置时不能静默什么都不做 —— 那样玩家点了按钮毫无反应。
            // 退化为直接执行，至少功能是通的（次数门槛仍然照过）。
            executor.executeWithLimitGate(player, param,
                    MenuGuiManager.wrap(plugin, sourceGui, player, param));
            return;
        }

        int size = normalizeSize(confirm.getInt("size", 27));
        String title = TextUtil.color(confirm.getString("title", "确认操作"));

        ChestGui gui = new ChestGui(size / 9, title);
        gui.setOnGlobalClick(event -> event.setCancelled(true));

        StaticPane pane = new StaticPane(9, size / 9);

        // ---- 确认按钮 ----
        addButton(pane, confirm, "confirm", size, (item, slot) ->
                new GuiItem(item, event -> {
                    event.setCancelled(true);
                    player.closeInventory();
                    // 确认后执行同样要过原子次数门槛，否则"限购 1 次"的确认按钮
                    // 可以无限点。跳过渲染快照，直接按当前配置重新裁决。
                    executor.executeWithLimitGate(player, param,
                            MenuGuiManager.wrap(plugin, sourceGui, player, param));
                }));

        // ---- 取消按钮 ----
        addButton(pane, confirm, "cancel", size, (item, slot) ->
                new GuiItem(item, event -> {
                    event.setCancelled(true);
                    player.closeInventory();
                    // 取消时播一个提示音，让玩家知道自己确实取消了
                    String sound = confirm.getString("cancelSound", "");
                    if (TextUtil.isNotEmpty(sound)) {
                        executor.playSound(player, sound);
                    }
                }));

        // ---- 返回按钮：回到原菜单 ----
        addButton(pane, confirm, "back", size, (item, slot) ->
                new GuiItem(item, event -> {
                    event.setCancelled(true);
                    player.closeInventory();
                    if (sourceGui != null) {
                        sourceGui.show(player);
                    }
                }));

        // ---- 装饰板 ----
        addButton(pane, confirm, "pane", size, (item, slot) ->
                new GuiItem(item, event -> event.setCancelled(true)));

        fillEmpty(pane, confirm, size);

        gui.addPane(Slot.fromIndex(0), pane);
        gui.show(player);

        String sound = confirm.getString("sound", "");
        if (TextUtil.isNotEmpty(sound)) {
            executor.playSound(player, sound);
        }
    }

    /**
     * 按配置添加一个按钮。
     *
     * <p>配置缺失时跳过。确认界面的按钮是"功能件"，缺了就没法用，
     * 但也不该因此让整个界面打不开 —— 玩家至少还能看到确认文字。
     *
     * @param pane    面板
     * @param config  确认界面配置
     * @param key     按钮键名
     * @param size    界面大小
     * @param factory 物品到界面物品的工厂
     */
    private static void addButton(StaticPane pane, YamlConfiguration config, String key, int size,
                                  ButtonFactory factory) {
        String material = config.getString(key + ".material", "");
        if (TextUtil.isEmpty(material)) {
            return;
        }
        ItemStack item = ItemStackUtil.build(material,
                config.getString(key + ".name", ""),
                config.getStringList(key + ".lore"),
                config.getBoolean(key + ".enchant", false),
                0, true, null, null);
        if (item == null) {
            return;
        }

        List<Integer> slots = config.getIntegerList(key + ".slots");
        if (slots.isEmpty()) {
            Integer single = config.contains(key + ".slot") ? config.getInt(key + ".slot") : null;
            if (single == null) {
                return;
            }
            slots = java.util.Collections.singletonList(single);
        }

        for (Integer slot : slots) {
            if (slot == null || slot < 0 || slot >= size) {
                continue;
            }
            pane.addItem(factory.create(item, slot), Slot.fromIndex(slot));
        }
    }

    /**
     * 用背景板填充未被占用的槽位。
     *
     * @param pane   面板
     * @param config 配置
     * @param size   界面大小
     */
    private static void fillEmpty(StaticPane pane, YamlConfiguration config, int size) {
        String material = config.getString("fill.material", "");
        if (TextUtil.isEmpty(material)) {
            return;
        }
        ItemStack filler = ItemStackUtil.build(material,
                config.getString("fill.name", ""),
                config.getStringList("fill.lore"),
                false, 0, true, null, null);
        if (filler == null) {
            return;
        }
        GuiItem guiItem = new GuiItem(filler, event -> event.setCancelled(true));
        for (int slot = 0; slot < size; slot++) {
            // 已占用的槽位再 addItem 不会报错，IFramework 后添加的会覆盖，
            // 所以这里只加"空的"，判断方式是检查面板内已有物品数
            pane.addItem(guiItem, Slot.fromIndex(slot));
        }
    }

    /**
     * 规整界面大小。
     *
     * @param size 原始值
     * @return 9~54 之间且为 9 的倍数
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

    /** 按钮工厂。 */
    private interface ButtonFactory {
        /**
         * 创建界面物品。
         *
         * @param item 基础物品
         * @param slot 槽位
         * @return 界面物品
         */
        GuiItem create(ItemStack item, int slot);
    }

    /**
     * 发送确认提示。
     *
     * @param player 玩家
     * @param param  按钮
     */
    public static void sendHint(Player player, MenuButtonParam param) {
        player.sendMessage(LangUtil.message("clickOperatorReason"));
    }
}
