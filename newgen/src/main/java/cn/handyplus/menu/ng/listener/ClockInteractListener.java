package cn.handyplus.menu.ng.listener;

import cn.handyplus.menu.ng.config.MenuConfig;
import cn.handyplus.menu.ng.core.MenuExecutor;
import cn.handyplus.menu.ng.inventory.ConfigProvider;
import cn.handyplus.menu.ng.inventory.InputManager;
import cn.handyplus.menu.ng.util.ItemStackUtil;
import cn.handyplus.menu.ng.util.SchedulerUtil;
import cn.handyplus.menu.ng.util.TextUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * 时钟物品监听。
 *
 * <p>玩家手里/背包里的"时钟"物品（由 {@code clock.name} 与 {@code clock.item} 配置决定）
 * 右键时打开指定菜单。这是把菜单从命令里解放出来的入口。
 *
 * <p>关键是<b>要取消事件</b>：不取消的话，玩家手里的时钟物品会真的去放置方块、
 * 打开箱子、激活红石 —— 右键任何方块都会同时触发菜单和方块交互。
 */
public final class ClockInteractListener implements Listener {

    private static final long CLICK_COOLDOWN_MILLIS = 400L;

    private final Plugin plugin;
    private final ConfigProvider configProvider;
    private final MenuExecutor executor;

    /**
     * 上次触发时间，防止一次右键被服务端拆成多个事件导致开两次界面。
     *
     * <p>静态：按玩家 UUID 记录，与监听器实例无关，这样玩家退出时
     * {@link #cleanup} 可以不带实例引用地清理，避免记录无界增长。
     */
    private static final java.util.Map<java.util.UUID, Long> lastTrigger = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 构造。
     *
     * @param plugin         插件
     * @param configProvider 配置提供者
     * @param executor       执行器
     */
    public ClockInteractListener(Plugin plugin, ConfigProvider configProvider,
                                 MenuExecutor executor) {
        this.plugin = plugin;
        this.configProvider = configProvider;
        this.executor = executor;
    }

    /**
     * 玩家交互事件。
     *
     * @param event 事件
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (player.isSneaking()) {
            // 潜行时不触发，避免和"放置方块"的常规意图冲突
            return;
        }
        if (InputManager.isWaiting(player)) {
            // 正在等待输入，此时右键不该再开菜单
            return;
        }

        MenuConfig config = configProvider.get();
        if (config == null || !config.isClockEnable()) {
            return;
        }
        String menu = config.getClockMenu();
        if (TextUtil.isEmpty(menu)) {
            return;
        }

        if (!matchesClockItem(player, event, config)) {
            return;
        }

        // 冷却去重
        long now = System.currentTimeMillis();
        Long last = lastTrigger.get(player.getUniqueId());
        if (last != null && now - last < CLICK_COOLDOWN_MILLIS) {
            return;
        }
        lastTrigger.put(player.getUniqueId(), now);

        // 确认要开菜单了才取消事件。放在前面取消的写法会误伤：
        // 玩家在等待输入时右键方块会莫名不生效。
        event.setCancelled(true);

        SchedulerUtil.runAtEntity(plugin, player, () ->
                schedulerOpen(player, config, menu));
    }

    /**
     * 判断手里的物品是否为时钟物品。
     *
     * @param player 玩家
     * @param event  事件
     * @param config 配置
     * @return 是则 true
     */
    private boolean matchesClockItem(Player player, PlayerInteractEvent event, MenuConfig config) {
        String expectName = config.getClockName();
        String expectItem = config.getClockItem();

        // 两者都没配 -> 任意手持物都能开菜单，这是配置错误而非特性，
        // 但原版就是这个行为，保持兼容以免破坏已有服务器的用法。
        if (TextUtil.isEmpty(expectName) && TextUtil.isEmpty(expectItem)) {
            return true;
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == org.bukkit.Material.AIR) {
            return false;
        }

        if (TextUtil.isNotEmpty(expectItem)) {
            String actual = hand.getType().name();
            if (!actual.equalsIgnoreCase(expectItem) && !actual.equalsIgnoreCase("LEGACY_" + expectItem)) {
                // 允许 "CLOCK" 与 "WATCH"（1.13 前的旧名）互相匹配
                boolean legacyMatch = ("WATCH".equalsIgnoreCase(expectItem) && "CLOCK".equalsIgnoreCase(actual))
                        || ("CLOCK".equalsIgnoreCase(expectItem) && "WATCH".equalsIgnoreCase(actual));
                if (!legacyMatch) {
                    return false;
                }
            }
        }

        if (TextUtil.isNotEmpty(expectName)) {
            String display = ItemStackUtil.displayName(hand);
            if (display == null) {
                return false;
            }
            // 比较去掉颜色代码后的纯文本，避免玩家给物品改了颜色就失效
            String plainExpect = TextUtil.stripColor(TextUtil.color(expectName));
            String plainActual = TextUtil.stripColor(display);
            return plainActual.contains(plainExpect) || plainActual.equals(plainExpect);
        }
        return true;
    }

    /**
     * 在玩家线程打开菜单。
     *
     * @param player 玩家
     * @param config 配置
     * @param menu   菜单名
     */
    private void schedulerOpen(Player player, MenuConfig config, String menu) {
        if (!player.isOnline()) {
            return;
        }
        executor.openMenu(player, menu);
    }

    /**
     * 清理玩家状态。
     *
     * @param player 玩家，可为 null
     */
    public static void cleanup(Player player) {
        if (player != null) {
            lastTrigger.remove(player.getUniqueId());
        }
    }
}
