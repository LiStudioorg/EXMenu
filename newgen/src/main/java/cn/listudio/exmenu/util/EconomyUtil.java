package cn.listudio.exmenu.util;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;

/**
 * Vault 经济桥接。
 *
 * <p>只做三件事：查余额、扣钱、加钱。用反射而非编译期依赖，这样 Vault 未安装时
 * 插件仍能加载 —— 只要菜单里没用到经济相关的按钮就不会有任何问题。
 *
 * <p>{@link #isReady()} 为 false 时，所有操作都返回"失败"而非抛异常，
 * 让调用方能把失败正常反馈给玩家。
 */
public final class EconomyUtil {

    /** Vault 的 Economy 实例。 */
    private static Object economy;

    /** {@code getBalance(OfflinePlayer)}。 */
    private static Method getBalance;

    /** {@code withdrawPlayer(OfflinePlayer, double)}。 */
    private static Method withdraw;

    /** {@code depositPlayer(OfflinePlayer, double)}。 */
    private static Method deposit;

    /** {@code format(double)}，可能不存在。 */
    private static Method format;

    private EconomyUtil() {
    }

    /**
     * 探测并挂接 Vault 经济。
     *
     * @return true 表示挂接成功
     */
    public static boolean init() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> provider =
                    Bukkit.getServicesManager().getRegistration(economyClass);
            if (provider == null) {
                return false;
            }
            economy = provider.getProvider();
            getBalance = economyClass.getMethod("getBalance", OfflinePlayer.class);
            withdraw = economyClass.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            deposit = economyClass.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            try {
                format = economyClass.getMethod("format", double.class);
            } catch (NoSuchMethodException ignored) {
                format = null;
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            economy = null;
            return false;
        }
    }

    /**
     * 经济系统是否可用。
     *
     * @return true 表示可用
     */
    public static boolean isReady() {
        return economy != null;
    }

    /**
     * 查询余额。
     *
     * @param player 玩家
     * @return 余额；经济不可用时返回 0
     */
    public static double getBalance(Player player) {
        if (!isReady() || player == null) {
            return 0D;
        }
        try {
            Object result = getBalance.invoke(economy, player);
            return result instanceof Number ? ((Number) result).doubleValue() : 0D;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0D;
        }
    }

    /**
     * 扣款。
     *
     * <p>Vault 的 {@code withdrawPlayer} 返回 {@code EconomyResponse}，
     * 其 {@code transactionSuccess} 字段为 boolean，需要反射读取。
     *
     * @param player 玩家
     * @param amount 金额
     * @return true 表示扣款成功
     */
    public static boolean withdraw(Player player, double amount) {
        if (!isReady() || player == null || amount <= 0) {
            return false;
        }
        try {
            Object response = withdraw.invoke(economy, player, amount);
            return isSuccess(response);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * 加钱。
     *
     * @param player 玩家
     * @param amount 金额
     * @return true 表示成功
     */
    public static boolean deposit(Player player, double amount) {
        if (!isReady() || player == null || amount <= 0) {
            return false;
        }
        try {
            Object response = deposit.invoke(economy, player, amount);
            return isSuccess(response);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * 读取 Vault 响应对象的成功标志。
     *
     * @param response {@code EconomyResponse}
     * @return true 表示交易成功
     */
    private static boolean isSuccess(Object response) {
        if (response == null) {
            return false;
        }
        try {
            Method method = response.getClass().getMethod("transactionSuccess");
            Object result = method.invoke(response);
            return result instanceof Boolean && (Boolean) result;
        } catch (ReflectiveOperationException e) {
            // 个别第三方 Economy 实现不提供该方法，退化为看 type 字段
            try {
                java.lang.reflect.Field field = response.getClass().getField("type");
                Object type = field.get(response);
                return type != null && "SUCCESS".equals(String.valueOf(type));
            } catch (ReflectiveOperationException ignored) {
                return false;
            }
        }
    }

    /**
     * 格式化金额显示。
     *
     * @param amount 金额
     * @return 格式化文本；Vault 不支持时返回两位小数的纯数字
     */
    public static String format(double amount) {
        if (format != null) {
            try {
                Object result = format.invoke(economy, amount);
                if (result != null) {
                    return String.valueOf(result);
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 落到下面的默认格式
            }
        }
        return String.format("%.2f", amount);
    }
}
