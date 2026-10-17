package cn.listudio.exmenu.util;

import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 动作栏（ActionBar）跨平台发送工具。
 *
 * <p>不同服务端的 API 完全不同：
 * <ul>
 *   <li>Paper / Folia：{@code Player#sendActionBar(String)}（编译期在 folia-api 中存在）</li>
 *   <li>Spigot：只有 {@code player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent[])}，
 *       且 bungee 相关类不在编译期依赖里，只能反射调用</li>
 * </ul>
 *
 * <p>因此这里做一次性探测（首调用时确定，之后走缓存路径）：
 * 先试原生 {@code sendActionBar}，失败再退回 bungee 反射。
 * 两条路都走不通时静默降级为普通聊天消息 —— 动作栏只是锦上添花，
 * 不该因为发不出去就中断整条动作链。
 */
public final class ActionBarUtil {

    /** 探测结果：0=未探测，1=原生 sendActionBar，2=bungee 反射，-1=不可用。 */
    private static volatile int mode;

    /** mode=1 时缓存的 Player#sendActionBar(String)。 */
    private static volatile Method nativeMethod;

    /** mode=2 时缓存的 Player#spigot()。 */
    private static volatile Method spigotMethod;

    /** mode=2 时缓存的 Spigot 分发器的 sendMessage(ChatMessageType, TextComponent[])。 */
    private static volatile Method sendMethod;

    /** mode=2 时缓存的 ChatMessageType.ACTION_BAR 枚举常量。 */
    private static volatile Object actionBarType;

    /** mode=2 时缓存的 TextComponent(String) 构造器。 */
    private static volatile Constructor<?> textComponentCtor;

    /** mode=2 时缓存的 TextComponent[] 数组类型。 */
    private static volatile Class<?> textComponentArray;

    private ActionBarUtil() {
    }

    /**
     * 向玩家发送一条动作栏消息。
     *
     * <p>必须在玩家所在线程调用（点击回调与动作链本来就满足这一前提）。
     *
     * @param player 玩家
     * @param text   文本（含 &amp; 颜色码，发送前转色）
     */
    public static void send(Player player, String text) {
        if (player == null || TextUtil.isEmpty(text)) {
            return;
        }
        String colored = TextUtil.color(text);
        detect();
        if (mode == 1) {
            try {
                nativeMethod.invoke(player, colored);
                return;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                // Spigot 上 sendActionBar 只有接口声明没有实现，invoke 抛
                // NoSuchMethodError（LinkageError 家族）—— 切换到 bungee 反射路径
                if (!fallbackToBungee()) {
                    player.sendMessage(colored);
                    return;
                }
            }
        }
        if (mode == 2) {
            try {
                Object spigot = spigotMethod.invoke(player);
                Object component = textComponentCtor.newInstance(colored);
                Object array = java.lang.reflect.Array.newInstance(textComponentArray.getComponentType(), 1);
                java.lang.reflect.Array.set(array, 0, component);
                sendMethod.invoke(spigot, actionBarType, array);
                return;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                // 落到降级路径
            }
        }
        // 降级：当普通消息发出，功能不丢，只是显示位置不同
        player.sendMessage(colored);
    }

    /**
     * 一次性探测当前服务端的动作栏 API 形态。
     */
    private static synchronized void detect() {
        if (mode != 0) {
            return;
        }
        try {
            Method method = Player.class.getMethod("sendActionBar", String.class);
            // 反射查找在运行期同样成立，但调用时若接口方法在 Spigot 实现里
            // 不存在会抛 NoSuchMethodError —— 这里直接用缓存的 Method 调玩家对象，
            // Player 接口方法在 Spigot 上必然存在声明，是否存在实现由 invoke 时验证
            nativeMethod = method;
            mode = 1;
            return;
        } catch (NoSuchMethodException ignored) {
            // 理论上 folia-api 编译期总能找到；运行期是否可用由首次调用验证
        }
        mode = -1;
    }

    /**
     * 原生方法首次真实调用失败后的回退探测（由 {@link #send} 的异常路径触发）。
     *
     * <p>Spigot 上 {@code Player#sendActionBar} 只有接口声明没有实现，
     * invoke 会抛 {@link NoSuchMethodError}。此时切换到 bungee 反射路径。
     *
     * @return true 表示成功切换到 bungee 路径
     */
    private static synchronized boolean fallbackToBungee() {
        if (mode == 2) {
            return true;
        }
        try {
            Class<?> messageType = Class.forName("net.md_5.bungee.api.ChatMessageType");
            Object actionBar = null;
            for (Object constant : messageType.getEnumConstants()) {
                if ("ACTION_BAR".equals(String.valueOf(constant))) {
                    actionBar = constant;
                    break;
                }
            }
            if (actionBar == null) {
                return false;
            }
            Class<?> componentType = Class.forName("net.md_5.bungee.api.chat.TextComponent");
            spigotMethod = Player.class.getMethod("spigot");
            textComponentCtor = componentType.getConstructor(String.class);
            textComponentArray = java.lang.reflect.Array.newInstance(componentType, 0).getClass();
            actionBarType = actionBar;
            sendMethod = spigotMethod.getReturnType()
                    .getMethod("sendMessage", messageType, textComponentArray);
            mode = 2;
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
