package cn.listudio.exmenu.listener;

import cn.listudio.exmenu.core.MenuExecutor;
import cn.listudio.exmenu.inventory.InputManager;
import cn.listudio.exmenu.inventory.MenuSession;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 玩家退出清理。
 *
 * <p>玩家退出时如果不清理会话状态，会留下两类隐患：
 * <ol>
 *   <li>输入会话残留 —— 玩家重连后说的第一句话会被当成上次的输入值</li>
 *   <li>管理员预览标记残留 —— 该 UUID 下次进入的菜单会自动跳过限制检查</li>
 * </ol>
 *
 * <p>用 {@code MONITOR} 优先级且不取消事件：清理是收尾工作，
 * 不应该影响其它插件对这个事件的判断。
 */
public final class PlayerQuitListener implements Listener {

    /**
     * 无参构造。
     *
     * <p>清理所需的 {@code InputManager} 与 {@code MenuExecutor} 都只用静态方法，
     * 没有实例状态需要注入，因此不持有任何字段。
     */
    public PlayerQuitListener() {
    }

    /**
     * 退出事件。
     *
     * @param event 事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        InputManager.cancel(player);
        MenuSession.cleanup(player);
        // 时钟监听器的触发时间戳按 UUID 记录，不清理会随玩家数无界增长
        ClockInteractListener.cleanup(player);
    }
}
