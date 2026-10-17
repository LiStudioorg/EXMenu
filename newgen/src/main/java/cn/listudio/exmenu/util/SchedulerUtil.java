package cn.listudio.exmenu.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 统一调度入口。
 *
 * <p>服务端可能是 Bukkit/Spigot，也可能是 Folia。Folia 把世界切成若干个 region，
 * 每个 region 有独立的 tick 线程，因此传统的 {@code Bukkit.getScheduler()} 不再安全。
 * 本类在类加载时探测一次运行环境，之后所有调度请求都走对应实现。
 *
 * <p>探测方式是检查 Folia 独有的类 {@code io.papermc.paper.threadedregions.RegionizedServer}
 * 是否存在 —— 这比读版本号字符串可靠，且不受服务端品牌（Paper/Folia 分支）影响。
 */
public final class SchedulerUtil {

    /** Folia 环境标记，类加载时确定，之后不再变化。 */
    private static final boolean FOLIA = detectFolia();

    private SchedulerUtil() {
    }

    /**
     * 探测当前是否为 Folia 系服务端。
     *
     * @return true 表示 Folia（或兼容 Folia 调度模型的分支）
     */
    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    /**
     * 当前是否运行在 Folia 系服务端上。
     *
     * @return true 表示 Folia
     */
    public static boolean isFolia() {
        return FOLIA;
    }

    /**
     * 在主线程（Folia 下为全局 region 线程）执行一次任务。
     *
     * <p>Bukkit 下等价于 {@code Bukkit.getScheduler().runTask(plugin, task)}；
     * Folia 下走 {@code getGlobalRegionScheduler()}。适用于不触碰具体世界/实体的操作，
     * 例如打开一个已由玩家线程准备好的界面、写内存状态。
     *
     * @param plugin 所属插件
     * @param task   任务体
     */
    public static void runTask(Plugin plugin, Runnable task) {
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> task.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * 延后指定 tick 数，在主线程/全局 region 线程执行一次任务。
     *
     * @param plugin 所属插件
     * @param task   任务体
     * @param delay  延迟 tick 数，小于 1 时按 1 处理（Folia 要求正数）
     */
    public static void runTaskLater(Plugin plugin, Runnable task, long delay) {
        long d = Math.max(1L, delay);
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, ignored -> task.run(), d);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, d);
        }
    }

    /**
     * 在异步线程执行一次任务。
     *
     * <p>异步任务里禁止触碰 Bukkit 的大部分 API（玩家背包、世界方块、实体属性等）。
     * 典型合法用途：数据库读写、HTTP 请求、纯计算。
     *
     * @param plugin 所属插件
     * @param task   任务体
     */
    public static void runTaskAsynchronously(Plugin plugin, Runnable task) {
        if (FOLIA) {
            Bukkit.getAsyncScheduler().runNow(plugin, ignored -> task.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    /**
     * 异步延迟执行一次任务。
     *
     * @param plugin 所属插件
     * @param task   任务体
     * @param delay  延迟毫秒数（Folia 侧单位为毫秒，Bukkit 侧换算为 tick）
     */
    public static void runTaskLaterAsynchronously(Plugin plugin, Runnable task, long delay) {
        if (FOLIA) {
            Bukkit.getAsyncScheduler().runDelayed(plugin, ignored -> task.run(),
                    Math.max(1L, delay), TimeUnit.MILLISECONDS);
        } else {
            // Bukkit 的异步调度以 tick 为单位，1 tick = 50ms
            long ticks = Math.max(1L, delay / 50L);
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, ticks);
        }
    }

    /**
     * 在"拥有该实体"的线程上执行任务。
     *
     * <p>这是 Folia 里最常用的调度形式：实体（含玩家）归属于某个 region，
     * 只有该 region 的线程才能安全改动它。若在 Folia 下已经处于正确的线程，
     * 任务会立即执行，不会多延迟一个 tick。
     *
     * <p>Bukkit 下退化为普通的主线程调度。
     *
     * @param plugin    所属插件
     * @param entity    归属实体，玩家操作一律传该玩家
     * @param task      任务体
     * @param retired   Folia 下实体被卸载/移除导致任务未能执行时的回调，可为 null
     */
    public static void runAtEntity(Plugin plugin, Entity entity, Runnable task, Consumer<Entity> retired) {
        if (entity == null) {
            runTask(plugin, task);
            return;
        }
        if (FOLIA) {
            entity.getScheduler().run(plugin, ignored -> task.run(), retired == null ? null : () -> retired.accept(entity));
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * 在拥有该实体的线程上执行任务（无 retired 回调）。
     *
     * @param plugin 所属插件
     * @param entity 归属实体
     * @param task   任务体
     */
    public static void runAtEntity(Plugin plugin, Entity entity, Runnable task) {
        runAtEntity(plugin, entity, task, null);
    }

    /**
     * 取消本插件所有的调度任务。
     *
     * <p>Bukkit 下清空 scheduler 队列；Folia 的调度器没有等价的批量取消入口，
     * 因此 Folia 下本方法为空操作 —— 这也符合 Folia 的设计：任务随 region/实体
     * 生命周期自动回收，插件卸载时不需要手工清理。
     *
     * @param plugin 所属插件
     */
    public static void cancelTasks(Plugin plugin) {
        if (!FOLIA) {
            Bukkit.getScheduler().cancelTasks(plugin);
        }
    }
}
