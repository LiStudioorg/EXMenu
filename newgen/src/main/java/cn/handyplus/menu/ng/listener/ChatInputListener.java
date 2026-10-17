package cn.handyplus.menu.ng.listener;

import cn.handyplus.menu.ng.util.LangUtil;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * 聊天输入监听。
 *
 * <p>玩家点了带 {@code input} 的按钮后，下一条聊天消息不再是聊天，而是按钮的输入值。
 *
 * <p>优先级必须设为 {@code LOWEST}：我们要在其它插件（聊天格式、禁言、聊天过滤）
 * 之前判断并取消事件。如果排在后面，被"禁言插件"取消的消息就永远进不了输入流程，
 * 玩家会觉得输入框没反应。
 */
public final class ChatInputListener implements Listener {

    private final ChatHandler handler;

    /**
     * 构造。
     *
     * @param handler 输入处理器
     */
    public ChatInputListener(ChatHandler handler) {
        this.handler = handler;
    }

    /**
     * 聊天事件。
     *
     * @param event 事件
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!handler.hasInput(event.getPlayer())) {
            return;
        }
        // 先取消再处理：无论输入合法与否，这条消息都不该被其他玩家看到。
        // 输入内容常常是购买数量之类的东西，广播出去很尴尬。
        event.setCancelled(true);

        String message = event.getMessage();
        if (message == null) {
            return;
        }
        // 百分号是 PAPI 变量语法，玩家输入无法安全替换，直接拒绝而不是悄悄改掉
        if (message.indexOf('%') >= 0) {
            event.getPlayer().sendMessage(LangUtil.message("noInput"));
            return;
        }

        handler.handle(event.getPlayer(), message.trim());
    }

    /** 输入处理器。 */
    public interface ChatHandler {
        /**
         * 玩家是否有待处理的输入。
         *
         * @param player 玩家
         * @return 有则 true
         */
        boolean hasInput(org.bukkit.entity.Player player);

        /**
         * 处理输入。
         *
         * @param player  玩家
         * @param message 输入内容
         */
        void handle(org.bukkit.entity.Player player, String message);
    }
}
