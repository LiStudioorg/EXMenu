package cn.listudio.exmenu.core;

import cn.listudio.exmenu.enter.MenuLimit;
import cn.listudio.exmenu.service.MenuLimitService;
import cn.listudio.exmenu.util.SchedulerUtil;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 限制记录的短时缓存（只读快照 + 异步刷新）。
 *
 * <p>存在的理由只有一条：<b>PAPI 的 onRequest 会在调用方线程执行</b> ——
 * 通常是渲染 Lore 的主线程，绝不允许在里面同步查数据库。
 * 而"剩余次数/冷却秒数"这类展示变量天然容忍秒级的陈旧值。
 *
 * <p>语义：
 * <ul>
 *   <li>命中且未过期 → 直接返回缓存行</li>
 *   <li>过期或没有 → 返回旧值（首次为 null），并排一次异步刷新</li>
 *   <li>点击占位/写入后调用 {@link #invalidate}，下一次读取立即拉新值</li>
 * </ul>
 *
 * <p>冷却剩余秒数不受缓存陈旧影响：缓存里存的是 {@code click_time}（不会自己变），
 * 剩余时间用 {@code cd - (now - clickTime)} 现场算，精度与墙钟一致。
 */
public final class LimitCache {

    /** 缓存有效期（毫秒）。 */
    private static final long TTL_MS = 15_000L;

    /** 单玩家条目。 */
    private static final class Entry {
        /** 数据快照（整表替换，读侧无需加锁）。 */
        volatile Map<Integer, MenuLimit> data;
        /** 上次刷新时刻。 */
        volatile long stamp;
        /** 是否有一次刷新在飞行中（防止重复排任务）。 */
        final java.util.concurrent.atomic.AtomicBoolean refreshing =
                new java.util.concurrent.atomic.AtomicBoolean(false);
    }

    private final Plugin plugin;
    private final MenuLimitService limitService;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    /**
     * 构造。
     *
     * @param plugin       插件实例（调度用）
     * @param limitService 限制 DAO
     */
    public LimitCache(Plugin plugin, MenuLimitService limitService) {
        this.plugin = plugin;
        this.limitService = limitService;
    }

    /**
     * 读取某玩家在某个按钮上的限制记录。
     *
     * <p>线程安全，可在任何线程调用；<b>永不执行阻塞 IO</b>。
     *
     * @param player     玩家
     * @param menuItemId 按钮 id
     * @return 记录；无数据时返回 null（值可能正待异步刷新）
     */
    public MenuLimit get(Player player, int menuItemId) {
        if (player == null) {
            return null;
        }
        UUID uuid = player.getUniqueId();
        Entry entry = entries.get(uuid);
        long now = System.currentTimeMillis();
        if (entry == null) {
            entry = new Entry();
            Entry previous = entries.putIfAbsent(uuid, entry);
            if (previous != null) {
                entry = previous;
            }
        }
        if (now - entry.stamp > TTL_MS) {
            refreshAsync(player.getName(), uuid);
        }
        Map<Integer, MenuLimit> data = entry.data;
        return data == null ? null : data.get(menuItemId);
    }

    /**
     * 使某玩家的缓存失效（写库后调用）。
     *
     * @param playerUuid 玩家 UUID
     */
    public void invalidate(UUID playerUuid) {
        if (playerUuid != null) {
            entries.remove(playerUuid);
        }
    }

    /**
     * 玩家退出时清理。
     *
     * @param playerUuid 玩家 UUID
     */
    public void remove(UUID playerUuid) {
        invalidate(playerUuid);
    }

    /**
     * 异步刷新某玩家的缓存。
     *
     * <p>同一玩家同时只会有一次刷新在飞行中。
     *
     * @param playerName 玩家名（DAO 写入需要）
     * @param playerUuid 玩家 UUID
     */
    public void refreshAsync(String playerName, UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        Entry entry = entries.computeIfAbsent(playerUuid, k -> new Entry());
        if (!entry.refreshing.compareAndSet(false, true)) {
            return;
        }
        SchedulerUtil.runTaskAsynchronously(plugin, () -> {
            try {
                Map<Integer, MenuLimit> fresh =
                        new HashMap<>(limitService.findMapByPlayerUuid(playerUuid));
                entry.data = fresh;
                entry.stamp = System.currentTimeMillis();
            } finally {
                entry.refreshing.set(false);
            }
        });
    }
}
