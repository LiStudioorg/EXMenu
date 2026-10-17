package cn.listudio.exmenu.inventory;

import cn.listudio.exmenu.constants.InputTypeEnum;
import cn.listudio.exmenu.core.MenuExecutor;
import cn.listudio.exmenu.util.LangUtil;
import cn.listudio.exmenu.util.SchedulerUtil;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天输入状态管理。
 *
 * <p>玩家点击带 {@code input} 的按钮后进入"等待输入"状态：界面关闭，
 * 下一条聊天消息被当作输入值。本类管理这个状态的进出。
 *
 * <p>三条必须遵守的规则：
 * <ol>
 *   <li><b>用 {@link ConcurrentHashMap}。</b>状态在异步的菜单渲染线程写入、
 *       在主线程的聊天事件里读取。</li>
 *   <li><b>输入值不放这里。</b>会话只存上下文；输入文本通过
 *       {@link MenuExecutor#setInput} 传递，避免两个状态源不同步。</li>
 *   <li><b>切换服务器/退出时必须清理。</b>否则玩家下次进来会发现自己的第一句话
 *       被当成了某个按钮的输入。</li>
 * </ol>
 */
public final class InputManager {

    /** 玩家 → 等待中的输入会话。 */
    private static final Map<UUID, InputSession> SESSIONS = new ConcurrentHashMap<>();

    private InputManager() {
    }

    /**
     * 进入输入状态。
     *
     * @param player         玩家
     * @param session        会话
     * @param timeoutSeconds 超时秒数，&lt;= 0 表示不超时
     */
    public static void begin(Player player, InputSession session, int timeoutSeconds) {
        if (player == null || session == null) {
            return;
        }
        SESSIONS.put(player.getUniqueId(), session);

        // 超时兜底：玩家开了输入界面却一直不说话，状态会永久残留，
        // 导致他之后发的每一句话都被吃掉。用一个延迟任务主动清理。
        if (timeoutSeconds > 0) {
            Plugin plugin = PluginHolder.get();
            if (plugin != null) {
                SchedulerUtil.runTaskLater(plugin, () -> {
                    if (player.isOnline() && isWaiting(player)) {
                        InputSession current = SESSIONS.get(player.getUniqueId());
                        if (current == session && current.isExpired(timeoutSeconds)) {
                            cancel(player);
                            player.sendMessage(LangUtil.message("inputTimeout"));
                        }
                    }
                }, timeoutSeconds * 20L);
            }
        }
    }

    /**
     * 玩家是否正在等待输入。
     *
     * @param player 玩家
     * @return true 表示等待中
     */
    public static boolean isWaiting(Player player) {
        return player != null && SESSIONS.containsKey(player.getUniqueId());
    }

    /**
     * 取当前会话。
     *
     * @param player 玩家
     * @return 会话，无则返回 null
     */
    public static InputSession get(Player player) {
        return player == null ? null : SESSIONS.get(player.getUniqueId());
    }

    /**
     * 结束输入状态。
     *
     * @param player 玩家
     * @return 被移除的会话，无则返回 null
     */
    public static InputSession end(Player player) {
        return player == null ? null : SESSIONS.remove(player.getUniqueId());
    }

    /**
     * 取消输入状态并提示玩家。
     *
     * @param player 玩家
     */
    public static void cancel(Player player) {
        end(player);
    }

    /**
     * 处理一次输入。
     *
     * <p>返回 true 表示这条消息已被消费，调用方应当取消聊天事件 ——
     * 否则玩家的输入会同时被广播出去。
     *
     * @param plugin 插件实例
     * @param player 玩家
     * @param message 原始消息
     * @param executor 执行器
     * @return true 表示已消费
     */
    public static boolean handle(Plugin plugin, Player player, String message,
                                 MenuExecutor executor) {
        InputSession session = get(player);
        if (session == null) {
            return false;
        }

        // 退出输入：用大写 T，与原版一致
        if ("T".equals(message)) {
            cancel(player);
            player.sendMessage(LangUtil.plain("inputExit"));
            reopen(plugin, player, session);
            return true;
        }

        InputTypeEnum type = InputTypeEnum.parse(session.getParam().getInputType());
        if (!type.validate(message)) {
            if (type == InputTypeEnum.NUMBER) {
                player.sendMessage(LangUtil.message("noNumber"));
            } else {
                player.sendMessage(LangUtil.message("noPositiveNumber"));
            }
            // 保留输入状态，让玩家重试而不是被迫重新点按钮
            return true;
        }

        end(player);
        // 输入值挂在按钮对象上，随会话一起跨线程传递。
        // 不能用 ThreadLocal：这里在聊天事件线程写入，下面在实体线程读取。
        MenuExecutor.setInput(session.getParam(), message);

        // 回到玩家所在线程执行 —— 聊天事件可能在异步线程触发，
        // 而动作链里的 openInventory / performCommand 都要求主线程
        SchedulerUtil.runAtEntity(plugin, player, () -> {
            if (session.getParam().isConfirm()) {
                ConfirmGuiManager.open(plugin, GuiHolder.provider(), player,
                        session.getParam(), session.getSourceGui(), executor);
                return;
            }

            // 输入完成后同样要过原子次数门槛：从点击到输入完成之间可能过了很久，
            // 期间玩家可能已经用掉名额，旧快照不足信。
            executor.executeWithLimitGate(player, session.getParam(),
                    MenuGuiManager.wrap(plugin, session.getSourceGui(), player, session.getParam()));
        });
        return true;
    }

    /**
     * 重新打开来源界面。
     *
     * @param plugin  插件
     * @param player  玩家
     * @param session 会话
     */
    private static void reopen(Plugin plugin, Player player, InputSession session) {
        MenuGuiManager manager = GuiHolder.get();
        if (manager == null) {
            return;
        }
        SchedulerUtil.runAtEntity(plugin, player, () -> {
            String menuName = session.getParam().getMenuName();
            if (menuName != null) {
                manager.open(player, menuName);
            }
        });
    }

    /**
     * 清理玩家状态。
     *
     * @param player 玩家
     */
    public static void cleanup(Player player) {
        if (player != null) {
            SESSIONS.remove(player.getUniqueId());
        }
    }

    /**
     * 当前等待输入的玩家数，用于诊断。
     *
     * @return 数量
     */
    public static int size() {
        return SESSIONS.size();
    }

    /**
     * 插件实例持有者。
     *
     * <p>用持有者而非静态字段直接注入，是为了让本类的静态方法与
     * 主类的生命周期解耦 —— 测试时可以在没有插件实例的情况下调用部分方法。
     */
    public static final class PluginHolder {
        /** 插件实例。 */
        private static Plugin plugin;

        private PluginHolder() {
        }

        /**
         * 设置插件实例。
         *
         * @param instance 插件
         */
        public static void set(Plugin instance) {
            plugin = instance;
        }

        /**
         * 取插件实例。
         *
         * @return 插件，未设置时返回 null
         */
        public static Plugin get() {
            return plugin;
        }
    }

    /** 配置持有者，供确认流程使用。 */
    public static final class ConfigHolder {
        /** 配置提供者。 */
        private static ConfigProvider provider;

        private ConfigHolder() {
        }

        /**
         * 设置配置提供者。
         *
         * @param configProvider 提供者
         */
        public static void set(ConfigProvider configProvider) {
            provider = configProvider;
        }

        /**
         * 取当前配置。
         *
         * @return 配置，未设置时返回 null
         */
        public static cn.listudio.exmenu.config.MenuConfig get() {
            return provider == null ? null : provider.get();
        }
    }

    /** 界面管理器持有者。 */
    public static final class GuiHolder {
        /** 界面管理器。 */
        private static MenuGuiManager manager;

        private GuiHolder() {
        }

        /**
         * 设置界面管理器。
         *
         * @param guiManager 管理器
         */
        public static void set(MenuGuiManager guiManager) {
            manager = guiManager;
        }

        /**
         * 取界面管理器。
         *
         * @return 管理器，未设置时返回 null
         */
        public static MenuGuiManager get() {
            return manager;
        }

        /**
         * 取配置提供者，即界面管理器本身。
         *
         * <p>{@code MenuGuiManager} 同时实现了 {@code GuiOpener} 与
         * {@code ConfigProvider}，因此不需要额外的持有者。
         *
         * @return 提供者，未设置时返回 null
         */
        public static ConfigProvider provider() {
            return manager;
        }
    }
}
