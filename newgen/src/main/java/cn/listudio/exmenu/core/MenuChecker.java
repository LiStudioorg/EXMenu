package cn.listudio.exmenu.core;

import cn.listudio.exmenu.enter.MenuLimit;
import cn.listudio.exmenu.param.MenuButtonParam;
import cn.listudio.exmenu.util.TextUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.List;
import java.util.Map;

/**
 * 按钮可用性检查。
 *
 * <p>玩家点下去之后、真正执行动作之前，一切拦截都发生在这里。检查分五关，
 * <b>顺序不可调换</b>：
 * <ol>
 *   <li>次数限制 —— 已达上限直接拒绝</li>
 *   <li>次数隐藏 —— 由渲染层处理，此处不重复</li>
 *   <li>冷却 —— 距上次点击不足冷却时间则拒绝</li>
 *   <li>点击类型 —— 按钮可能只响应特定键</li>
 *   <li>自定义条件 —— 最后一道，因为它的成本最高（要跑 PAPI）</li>
 * </ol>
 *
 * <p>把最便宜的检查放前面是刻意的：点击是高频操作，而自定义条件可能触发十几次
 * PAPI 变量解析，放在前面会明显拖慢无效点击的响应。
 *
 * <p>本类不修改任何状态 —— 记录点击是 {@link MenuExecutor} 的职责。这样划分是因为
 * <b>检查阶段可能因商店校验失败而中止，此时不应留下任何使用记录</b>。
 */
public final class MenuChecker {

    private MenuChecker() {
    }

    /**
     * 检查结果。
     *
     * <p>用枚举而非 boolean，是为了让调用方知道<b>为什么</b>被拒绝 —— 不同的拒绝
     * 原因要播不同的提示音、发不同的消息。原版只返回 boolean，导致所有失败
     * 都只能给一句笼统的"操作未完成"。
     */
    public enum Result {
        /** 允许执行。 */
        ALLOWED,
        /** 次数已用尽。 */
        LIMIT_REACHED,
        /** 仍在冷却中。 */
        COOLING_DOWN,
        /** 点击类型不匹配。 */
        WRONG_CLICK_TYPE,
        /** 自定义条件不满足。 */
        CONDITION_FAILED
    }

    /**
     * 综合检查一个按钮能否执行。
     *
     * @param player 玩家
     * @param param  按钮
     * @param limit  该玩家在此按钮上的限制记录，可为 null
     * @param config 插件配置，提供 action.cd / action.limit 开关
     * @return 检查结果
     */
    public static Result check(Player player, MenuButtonParam param, MenuLimit limit, ConfigAccessor config) {
        if (player == null || param == null) {
            return Result.CONDITION_FAILED;
        }

        // ---- 1. 次数限制 ----
        if (param.getLimit() > 0 && config.isActionLimit()) {
            int used = limit == null || limit.getNumber() == null ? 0 : limit.getNumber();
            if (used >= param.getLimit()) {
                return Result.LIMIT_REACHED;
            }
        }

        // ---- 2. 冷却 ----
        if (param.getCd() > 0 && config.isActionCd()) {
            long last = limit == null || limit.getClickTime() == null ? 0L : limit.getClickTime();
            if (last > 0L && System.currentTimeMillis() - last < param.getCd()) {
                return Result.COOLING_DOWN;
            }
        }

        // ---- 3. 点击类型 ----
        if (!isClickTypeAllowed(param)) {
            return Result.WRONG_CLICK_TYPE;
        }

        // ---- 4. 自定义条件 ----
        if (checkConditions(player, param.getConditions())) {
            return Result.CONDITION_FAILED;
        }

        return Result.ALLOWED;
    }

    /**
     * 判断当前点击类型是否被该按钮接受。
     *
     * <p>规则：只要配置了对应键的动作，就允许该键。没有配置，则回退到主
     * {@code commands} —— 也就是说，一个只有 {@code commands} 的按钮对任何键都响应。
     *
     * @param param 按钮
     * @return true 表示允许
     */
    private static boolean isClickTypeAllowed(MenuButtonParam param) {
        ClickType clickType = param.getEventClickType();
        if (clickType == null) {
            // 未注入点击类型（例如键盘触发），不做限制
            return true;
        }

        boolean hasLeft = !param.getActionsLeft().isEmpty();
        boolean hasRight = !param.getActionsRight().isEmpty();
        boolean hasMiddle = !param.getActionsMiddle().isEmpty();

        // 三种专属动作都没配 —— 回退到通用 commands，任何键都算合法
        if (!hasLeft && !hasRight && !hasMiddle) {
            return true;
        }

        switch (clickType) {
            case LEFT:
                return hasLeft;
            case RIGHT:
                return hasRight;
            case MIDDLE:
                return hasMiddle;
            default:
                // 其它点击类型（SHIFT_LEFT 等）统一不允许，避免误触发
                return false;
        }
    }

    /**
     * 检查显示条件，全部满足才算通过。
     *
     * @param player     玩家
     * @param conditions 条件表达式列表
     * @return true 表示<b>有</b>条件不满足（注意语义：返回 true 是"失败"）
     */
    public static boolean checkConditions(Player player, List<String> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return false;
        }
        for (String condition : conditions) {
            if (TextUtil.isEmpty(condition)) {
                continue;
            }
            String resolved = cn.listudio.exmenu.util.PlaceholderUtil.set(player, condition);
            if (!ConditionEvaluator.evaluate(resolved)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查隐藏条件，任一满足即隐藏。
     *
     * @param player     玩家
     * @param conditions 条件表达式列表
     * @return true 表示应当隐藏
     */
    public static boolean checkConditionsHide(Player player, List<String> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return false;
        }
        for (String condition : conditions) {
            if (TextUtil.isEmpty(condition)) {
                continue;
            }
            String resolved = cn.listudio.exmenu.util.PlaceholderUtil.set(player, condition);
            // 这里与显示条件的差别：格式非法时跳过（视为不隐藏），
            // 因为"隐藏"是破坏性操作，配置写错不应导致按钮消失。
            if (resolved.contains(">") || resolved.contains("<")
                    || resolved.contains("=") || resolved.contains("!")) {
                if (ConditionEvaluator.evaluate(resolved)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判断剩余可用次数。
     *
     * @param param 按钮
     * @param limit 限制记录，可为 null
     * @return 剩余次数；无限制时返回 {@link Integer#MAX_VALUE}
     */
    public static int remaining(MenuButtonParam param, MenuLimit limit) {
        if (param.getLimit() <= 0) {
            return Integer.MAX_VALUE;
        }
        int used = limit == null || limit.getNumber() == null ? 0 : limit.getNumber();
        return Math.max(0, param.getLimit() - used);
    }

    /**
     * 判断剩余冷却毫秒数。
     *
     * @param param 按钮
     * @param limit 限制记录，可为 null
     * @return 剩余毫秒；已结束或无冷却时返回 0
     */
    public static long remainingCd(MenuButtonParam param, MenuLimit limit) {
        if (param.getCd() <= 0) {
            return 0L;
        }
        long last = limit == null || limit.getClickTime() == null ? 0L : limit.getClickTime();
        if (last <= 0L) {
            return 0L;
        }
        long elapsed = System.currentTimeMillis() - last;
        return Math.max(0L, param.getCd() - elapsed);
    }

    /**
     * 限制记录的只读视图。
     *
     * <p>渲染层需要一次性拿到某玩家的全部记录，但又不想依赖具体的 DAO 实现，
     * 因此抽出这个最小接口。
     */
    public interface LimitView {
        /**
         * 取某按钮的记录。
         *
         * @param menuItemId 按钮 id
         * @return 记录，可为 null
         */
        MenuLimit get(Integer menuItemId);

        /**
         * 全部记录的映射。
         *
         * @return 以按钮 id 为键的映射
         */
        Map<Integer, MenuLimit> all();
    }

    /**
     * 配置访问的最小接口。
     *
     * <p>检查器只需要四个开关，不希望依赖整个 {@code MenuConfig}。
     */
    public interface ConfigAccessor {
        /** @return 是否启用冷却 */
        boolean isActionCd();

        /** @return 是否启用次数限制 */
        boolean isActionLimit();

        /** @return 是否启用金钱动作 */
        boolean isActionMoney();

        /** @return 是否启用点券动作 */
        boolean isActionPoint();
    }
}
