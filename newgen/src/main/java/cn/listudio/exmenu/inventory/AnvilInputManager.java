package cn.listudio.exmenu.inventory;

import cn.listudio.exmenu.constants.InputTypeEnum;
import cn.listudio.exmenu.core.MenuExecutor;
import cn.listudio.exmenu.param.MenuButtonParam;
import cn.listudio.exmenu.util.ItemStackUtil;
import cn.listudio.exmenu.util.LangUtil;
import cn.listudio.exmenu.util.SchedulerUtil;
import cn.listudio.exmenu.util.TextUtil;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.AnvilGui;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.util.Slot;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 铁砧输入通道。
 *
 * <p>按钮配置 {@code inputMethod: ANVIL}（配合非空 {@code input}）时，
 * 点击按钮弹出铁砧界面收集文本，替代聊天栏输入。相较聊天输入：
 * <ul>
 *   <li>不占用聊天事件 —— 没有"消息被别的插件抢先处理"的整类问题</li>
 *   <li>输入不会广播，也天然没有"退出输入"的额外手势（关界面即取消）</li>
 *   <li>对新手更直观：看得见一个输入框</li>
 * </ul>
 *
 * <p>交互契约：<b>在重命名栏输入文本，点击输出槽（铁砧最右格）提交；
 * 直接关闭界面视为取消</b>，回到来源菜单。
 *
 * <p>校验规则与聊天输入完全一致：拒绝含 {@code %} 的输入（防 PAPI 注入），
 * 数值类型按 {@link InputTypeEnum} 校验；校验失败<b>不关界面</b>，玩家可继续修改。
 */
public final class AnvilInputManager {

    /** 铁砧输出槽的 raw slot。 */
    private static final int RESULT_SLOT = 2;

    private AnvilInputManager() {
    }

    /**
     * 打开铁砧输入界面。
     *
     * @param plugin    插件实例
     * @param player    玩家
     * @param param     按钮（点击路径的复制件，可安全写入）
     * @param sourceGui 来源菜单界面，取消时返回
     * @param executor  执行器
     */
    public static void begin(Plugin plugin, Player player, MenuButtonParam param,
                             ChestGui sourceGui, MenuExecutor executor) {
        InputTypeEnum type = InputTypeEnum.parse(param.getInputType());
        String prompt = buildPrompt(param, type);

        AnvilGui gui = Guis.anvil(prompt, plugin);

        // 底版纸：铁砧必须有输入物品，重命名栏才可编辑
        ItemStack base = ItemStackUtil.build("PAPER",
                "&e点击右侧输出格提交", java.util.Collections.emptyList(),
                false, 0, true, null, null);
        if (base != null) {
            gui.getFirstItemComponent().setItem(new GuiItem(base), Slot.fromIndex(0));
        }

        AtomicReference<String> typed = new AtomicReference<>("");
        AtomicBoolean finished = new AtomicBoolean(false);

        gui.setOnNameInputChanged(text -> typed.set(text == null ? "" : text));

        gui.setOnGlobalClick(event -> {
            event.setCancelled(true);
            if (event.getClickedInventory() == null
                    || !event.getClickedInventory().equals(gui.getInventory())) {
                return;
            }
            if (event.getRawSlot() != RESULT_SLOT) {
                return;
            }
            String text = typed.get().trim();

            // 与聊天输入同样的 % 拒绝规则：防止玩家借输入注入 PAPI 变量
            if (text.indexOf('%') >= 0) {
                player.sendMessage(LangUtil.message("noInput"));
                return;
            }
            if (!type.validate(text)) {
                switch (type) {
                    case NUMBER:
                        player.sendMessage(LangUtil.message("noNumber"));
                        break;
                    case POSITIVE_NUMBER:
                        player.sendMessage(LangUtil.message("noPositiveNumber"));
                        break;
                    default:
                        player.sendMessage(LangUtil.message("noInput"));
                        break;
                }
                return;
            }

            finished.set(true);
            MenuExecutor.setInput(param, text);
            player.closeInventory();
            InputManager.finish(plugin, player, new InputSession(param, sourceGui), executor);
        });

        // 关界面 = 取消输入，回到来源菜单（与聊天输入的大写 T 退出等价）
        gui.setOnClose(event -> {
            if (finished.get()) {
                return;
            }
            finished.set(true);
            SchedulerUtil.runAtEntity(plugin, player, () -> {
                if (sourceGui != null && player.isOnline()) {
                    sourceGui.show(player);
                }
            });
        });

        gui.show(player);
    }

    /**
     * 组装铁砧界面标题。
     *
     * <p>{@code input} 可能是类型名（NUMBER）也可能是提示文本：
     * 类型名转成友好描述，纯文本原样用作标题（截断防止标题过长）。
     *
     * @param param 按钮
     * @param type  解析后的输入类型
     * @return 界面标题
     */
    private static String buildPrompt(MenuButtonParam param, InputTypeEnum type) {
        String input = param.getInput() == null ? "" : param.getInput().trim();
        String body;
        if (input.equalsIgnoreCase(type.name()) || isTypeName(input)) {
            body = "请输入" + type.getDescription();
        } else {
            body = input;
        }
        if (body.length() > 40) {
            body = body.substring(0, 40);
        }
        return TextUtil.color("&8" + body);
    }

    /**
     * 判断字符串是否是输入类型名。
     *
     * @param text 文本
     * @return true 表示是类型名
     */
    private static boolean isTypeName(String text) {
        if (TextUtil.isEmpty(text)) {
            return false;
        }
        for (InputTypeEnum value : InputTypeEnum.values()) {
            if (value.name().equalsIgnoreCase(text)) {
                return true;
            }
        }
        return false;
    }
}
