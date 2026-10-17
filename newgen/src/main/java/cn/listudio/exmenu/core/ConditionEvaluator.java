package cn.listudio.exmenu.core;

import cn.listudio.exmenu.util.TextUtil;

/**
 * 条件表达式求值器。
 *
 * <p>配置里的条件形如 {@code %player_level% >= 10}、{@code %vault_eco_balance% > 1000}。
 * PAPI 替换发生在求值之前，因此这里处理的已经是纯数值比较。
 *
 * <p><b>两个必须遵守的规则，它们各自对应一个原版真实缺陷：</b>
 *
 * <ol>
 *   <li><b>双字符运算符必须优先于单字符匹配。</b>若按 {@code >} 先匹配，
 *       那么 {@code >=} 会被当成 {@code >} 处理，右边留下一个 {@code =} 开头的字符串，
 *       解析成数字时变成 0 —— 于是 {@code level >= 10} 退化成 {@code level > 0}，
 *       几乎所有玩家都通过。因此这里先试 {@code >=}、{@code <=}、{@code !=}、{@code ==}，
 *       全部失败后才试单字符。</li>
 *   <li><b>数值解析失败必须视为"条件不满足"而非"等于 0"。</b>当 PAPI 变量不存在时，
 *       替换结果是原样的 {@code %xxx%}，此时若按 0 参与比较，会让
 *       {@code %xxx% &lt;= 0} 这类条件意外成立。这里选择保守拒绝。</li>
 * </ol>
 *
 * <p>本类所有方法都是纯函数，无状态、线程安全。
 */
public final class ConditionEvaluator {

    /** 求值结果：不满足。 */
    private static final boolean NOT_SATISFIED = false;

    /** 求值结果：满足。 */
    private static final boolean SATISFIED = true;

    private ConditionEvaluator() {
    }

    /**
     * 判断一个条件表达式是否成立。
     *
     * @param expression 已做 PAPI 替换的条件表达式，例如 {@code 15 >= 10}
     * @return true 表示条件成立
     */
    public static boolean evaluate(String expression) {
        if (TextUtil.isEmpty(expression)) {
            return NOT_SATISFIED;
        }

        String text = expression.trim();

        // 顺序非常重要：先长后短，避免 >= 被 > 截断
        if (text.contains(">=")) {
            return compare(text, ">=", Comparison.AT_LEAST);
        }
        if (text.contains("<=")) {
            return compare(text, "<=", Comparison.AT_MOST);
        }
        if (text.contains("!=")) {
            return compare(text, "!=", Comparison.NOT_EQUAL);
        }
        if (text.contains("==")) {
            return compare(text, "==", Comparison.EQUAL);
        }
        // 到这里才轮到单字符：= 放在 == 之后，语义上是"等于"
        if (text.contains("=")) {
            return compare(text, "=", Comparison.EQUAL);
        }
        if (text.contains(">")) {
            return compare(text, ">", Comparison.GREATER);
        }
        if (text.contains("<")) {
            return compare(text, "<", Comparison.LESS);
        }

        // 没有任何运算符：非空且不为 false/0 即视为"开关式"条件
        return parseBoolean(text);
    }

    /**
     * 按指定运算符比较左右两侧。
     *
     * <p>优先数值比较；两侧都不是数字时退化为字符串比较（仅对 {@code =} / {@code !=}
     * 有意义，大小比较在字符串上按字典序）。
     *
     * @param text       表达式全文
     * @param operator   运算符
     * @param comparison 比较语义
     * @return true 表示成立
     */
    private static boolean compare(String text, String operator, Comparison comparison) {
        int index = text.indexOf(operator);
        if (index < 0) {
            return NOT_SATISFIED;
        }

        String left = text.substring(0, index).trim();
        String right = text.substring(index + operator.length()).trim();

        Double leftNumber = parseNumber(left);
        Double rightNumber = parseNumber(right);

        if (leftNumber != null && rightNumber != null) {
            switch (comparison) {
                case GREATER:
                    return leftNumber > rightNumber;
                case LESS:
                    return leftNumber < rightNumber;
                case AT_LEAST:
                    return leftNumber >= rightNumber;
                case AT_MOST:
                    return leftNumber <= rightNumber;
                case EQUAL:
                    return leftNumber.doubleValue() == rightNumber.doubleValue();
                case NOT_EQUAL:
                    return leftNumber.doubleValue() != rightNumber.doubleValue();
                default:
                    return NOT_SATISFIED;
            }
        }

        // 非数值：只有相等/不等可以判定。
        // 大小比较在非数值上无意义，保守返回不满足，避免误放行。
        switch (comparison) {
            case EQUAL:
                return left.equals(right);
            case NOT_EQUAL:
                return !left.equals(right);
            default:
                return NOT_SATISFIED;
        }
    }

    /**
     * 尝试把字符串解析为数值。
     *
     * <p>关键在于：解析失败返回 {@code null} 而不是 0。把失败当 0 会让
     * 未替换成功的 PAPI 变量意外满足条件。
     *
     * @param text 待解析字符串
     * @return 数值，无法解析时返回 null
     */
    private static Double parseNumber(String text) {
        if (TextUtil.isEmpty(text)) {
            return null;
        }
        String value = text.trim();
        // 去掉可能残留的 PAPI 包裹，例如 "%player_level%"
        if (value.startsWith("%") || value.endsWith("%")) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 把无运算符的表达式解释为布尔。
     *
     * @param text 表达式
     * @return true 表示非空、非 {@code false}、非 {@code 0}
     */
    private static boolean parseBoolean(String text) {
        if (TextUtil.isEmpty(text)) {
            return NOT_SATISFIED;
        }
        String value = text.trim();
        if (value.equalsIgnoreCase("false") || value.equals("0")) {
            return NOT_SATISFIED;
        }
        if (value.startsWith("%") && value.endsWith("%")) {
            // 未被替换的变量：视为不满足
            return NOT_SATISFIED;
        }
        return SATISFIED;
    }

    /** 比较语义。 */
    private enum Comparison {
        /** 大于。 */
        GREATER,
        /** 小于。 */
        LESS,
        /** 大于等于。 */
        AT_LEAST,
        /** 小于等于。 */
        AT_MOST,
        /** 等于。 */
        EQUAL,
        /** 不等于。 */
        NOT_EQUAL
    }
}
