package cn.handyplus.menu.ng.inventory;

import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 界面会话状态。
 *
 * <p>保存"玩家当前处于哪种特殊界面状态"。目前只有一种：管理员预览模式。
 *
 * <p>为什么需要它：管理员用 {@code /plm view} 预览菜单时，应当绕过权限与
 * 世界黑名单检查，否则管理员在自己没有权限的菜单面前会被挡在外面，
 * 而预览的意义正是"看看这个菜单长什么样"。但绕过又不能让普通玩家沾光，
 * 所以需要一个"这次打开是管理员预览"的标记。
 *
 * <p>用 {@link ConcurrentHashMap} 而非普通 Map：查看与写入可能分别发生在
 * 主线程与异步的菜单渲染线程上。
 *
 * <p><b>标记必须及时清除</b>，否则玩家退出预览状态后仍能无限次打开受限菜单。
 * 清除时机有三处：打开普通菜单时、玩家退出时、以及一个短超时兜底。
 */
public final class MenuSession {

    /** 处于管理员预览状态的玩家。 */
    private static final Set<UUID> ADMIN_VIEW = ConcurrentHashMap.newKeySet();

    /** 标记的有效期（毫秒）。防止因异常路径未清除而永久残留。 */
    private static final long TTL = 10_000L;

    /** 每个标记的写入时刻。 */
    private static final java.util.Map<UUID, Long> STAMP = new ConcurrentHashMap<>();

    private MenuSession() {
    }

    /**
     * 标记为管理员预览。
     *
     * @param player 玩家
     */
    public static void setAdminView(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        ADMIN_VIEW.add(uuid);
        STAMP.put(uuid, System.currentTimeMillis());
    }

    /**
     * 清除管理员预览标记。
     *
     * @param player 玩家
     */
    public static void clearAdminView(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        ADMIN_VIEW.remove(uuid);
        STAMP.remove(uuid);
    }

    /**
     * 判断玩家是否处于管理员预览状态。
     *
     * <p>带超时检查：超过 {@link #TTL} 未刷新即视为过期并自动清除。
     * 这是防御性设计 —— 万一某条异常路径漏掉了清除，最多 10 秒后也会自愈，
     * 而不是让玩家永久保留绕过权限的能力。
     *
     * @param player 玩家
     * @return true 表示处于预览状态
     */
    public static boolean isAdminView(Player player) {
        if (player == null) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        if (!ADMIN_VIEW.contains(uuid)) {
            return false;
        }
        Long stamp = STAMP.get(uuid);
        if (stamp == null || System.currentTimeMillis() - stamp > TTL) {
            clearAdminView(player);
            return false;
        }
        return true;
    }

    /**
     * 清理指定玩家的全部状态。
     *
     * <p>玩家退出时调用。不做清理会随在线人数增长而泄漏内存 ——
     * 虽然只是两个小对象，但长期运行仍应保持干净。
     *
     * @param player 玩家
     */
    public static void cleanup(Player player) {
        clearAdminView(player);
    }
}
