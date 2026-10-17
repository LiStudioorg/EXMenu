package cn.listudio.exmenu.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * PlaceholderAPI 桥接。
 *
 * <p>PlayerMenu 大量使用 PAPI 变量（菜单名、按钮名、条件表达式、命令参数），
 * 因此这里做了三层处理：
 * <ol>
 *   <li>PAPI 未安装时，{@link #set} 原样返回输入 —— 让配置里写了变量的服务器
 *       仍能正常运行，只是变量不被替换</li>
 *   <li>替换时以 {@code null} 作为玩家上下文，用于解析全局变量（如 {@code %server_online%}）</li>
 *   <li>解析异常被吞掉并返回原串 —— 单个坏变量不应让整个菜单渲染失败</li>
 * </ol>
 */
public final class PlaceholderUtil {

    /** PAPI 是否可用，初始化时探测一次。 */
    private static boolean enabled;

    /** PAPI 的 {@code setPlaceholders} 方法引用，避免每次调用都反射查找。 */
    private static java.lang.reflect.Method setPlaceholdersMethod;

    private PlaceholderUtil() {
    }

    /**
     * 探测并初始化 PAPI。
     *
     * <p>用反射而非编译期依赖，因为 PAPI 是可选插件。
     * 这样的代价是首次调用略慢，但只需一次。
     *
     * @return true 表示 PAPI 可用
     */
    public static boolean init() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            enabled = false;
            return false;
        }
        try {
            Class<?> clazz = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            setPlaceholdersMethod = clazz.getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class);
            enabled = true;
        } catch (ReflectiveOperationException e) {
            enabled = false;
        }
        return enabled;
    }

    /**
     * PAPI 是否可用。
     *
     * @return true 表示可用
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * 替换字符串中的 PAPI 变量。
     *
     * @param player 上下文玩家，可为 null（解析全局变量）
     * @param text   原始文本
     * @return 替换后的文本；PAPI 不可用或入参为空时原样返回
     */
    public static String set(Player player, String text) {
        if (!enabled || text == null || text.isEmpty()) {
            return text;
        }
        // 没有 % 的字符串无需进入 PAPI，避免无谓开销 —— 菜单渲染时这是热路径
        if (text.indexOf('%') < 0) {
            return text;
        }
        try {
            Object result = setPlaceholdersMethod.invoke(null, player, text);
            return result == null ? text : String.valueOf(result);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 单个变量解析失败不应影响整个菜单
            return text;
        }
    }

    /**
     * 替换一组字符串中的 PAPI 变量。
     *
     * @param player 上下文玩家
     * @param lines  原始文本列表
     * @return 新列表，永不返回 null
     */
    public static java.util.List<String> set(Player player, java.util.List<String> lines) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (lines == null) {
            return result;
        }
        for (String line : lines) {
            result.add(set(player, line));
        }
        return result;
    }
}
