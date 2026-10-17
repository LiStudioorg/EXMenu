package cn.listudio.exmenu.util;

import net.md_5.bungee.api.ChatColor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 颜色与文本处理工具。
 *
 * <p>支持三类写法：
 * <ul>
 *   <li>传统 {@code &a} / {@code §a} 颜色与格式码</li>
 *   <li>十六进制 {@code &amp;#RRGGBB} 或 {@code &amp;x&amp;R&amp;R&amp;G&amp;G&amp;B&amp;B}</li>
 *   <li>MiniMessage 风格标签不在此处理（交由 GUI 层决定是否解析）</li>
 * </ul>
 *
 * <p>十六进制支持要求服务端为 1.16+（Bungee ChatColor.of 的限制）；
 * 低版本服务端会自动退化为去掉十六进制序列，不会抛异常。
 */
public final class TextUtil {

    /** 形如 {@code &#RRGGBB} 的十六进制颜色。 */
    private static final Pattern HEX_HASH = Pattern.compile("(?i)&#([0-9a-f]{6})");

    /** 形如 {@code &x&R&R&G&G&B&B} 的原版十六进制写法。 */
    private static final Pattern HEX_VANILLA = Pattern.compile("(?i)&x(&[0-9a-f]){6}");

    /** 服务端是否支持 {@link ChatColor#of(String)}（1.16+）。 */
    private static final boolean HEX_SUPPORTED = detectHexSupport();

    private TextUtil() {
    }

    /**
     * 探测服务端是否支持 RGB 颜色。
     *
     * @return true 表示可调用 {@code ChatColor.of}
     */
    private static boolean detectHexSupport() {
        try {
            ChatColor.class.getMethod("of", String.class);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    /**
     * 将文本中的颜色代码转换为可显示的样式。
     *
     * @param text 原始文本，可为 null
     * @return 转换后的文本；入参为 null 时返回空串
     */
    public static String color(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String result = text;
        if (HEX_SUPPORTED) {
            result = translateHexHash(result);
            result = translateHexVanilla(result);
        } else {
            // 低版本服务端：剥掉十六进制写法，避免把 &x&1&2&3... 原样显示出来
            result = HEX_HASH.matcher(result).replaceAll("");
            result = HEX_VANILLA.matcher(result).replaceAll("");
        }
        return ChatColor.translateAlternateColorCodes('&', result);
    }

    /**
     * 转换 {@code &#RRGGBB} 形式。
     *
     * @param text 原始文本
     * @return 已替换为 §x§R§R§G§G§B§B 的文本
     */
    private static String translateHexHash(String text) {
        Matcher matcher = HEX_HASH.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(toSectionSign(matcher.group(1))));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * 转换 {@code &x&R&R&G&G&B&B} 形式。
     *
     * @param text 原始文本
     * @return 已替换为 §x§R§R§G§G§B§B 的文本
     */
    private static String translateHexVanilla(String text) {
        Matcher matcher = HEX_VANILLA.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            // 去掉每个字符前的 &，整体前面换成 §x
            String digits = matcher.group().substring(2).replace("&", "");
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(toSectionSign(digits)));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * 把 6 位十六进制数字串转成 {@code §x§R§R§G§G§B§B}。
     *
     * @param digits 6 位十六进制数字
     * @return 带 § 前缀的字符串
     */
    private static String toSectionSign(String digits) {
        StringBuilder sb = new StringBuilder("\u00a7x");
        for (char c : digits.toCharArray()) {
            sb.append('\u00a7').append(c);
        }
        return sb.toString();
    }

    /**
     * 对列表中的每一项做颜色转换。
     *
     * @param lines 原始文本列表，可为 null
     * @return 转换后的列表；入参为 null 时返回空列表而非 null
     */
    public static List<String> color(List<String> lines) {
        List<String> result = new ArrayList<>();
        if (lines == null) {
            return result;
        }
        for (String line : lines) {
            result.add(color(line));
        }
        return result;
    }

    /**
     * 去掉文本中全部颜色代码，得到纯文本（用于长度计算、日志）。
     *
     * @param text 原始文本，可为 null
     * @return 无颜色代码的文本
     */
    public static String stripColor(String text) {
        if (text == null) {
            return "";
        }
        return ChatColor.stripColor(color(text));
    }

    /**
     * 判断字符串是否为 null 或空白。
     *
     * <p>替代 HandyLib 的 {@code StrUtil.isEmpty}，语义一致：null 与 "" 都算空。
     *
     * @param text 待检查字符串
     * @return true 表示为 null 或长度 0
     */
    public static boolean isEmpty(CharSequence text) {
        return text == null || text.length() == 0;
    }

    /**
     * 判断字符串是否非空。
     *
     * @param text 待检查字符串
     * @return true 表示非 null 且长度大于 0
     */
    public static boolean isNotEmpty(CharSequence text) {
        return !isEmpty(text);
    }

    /**
     * 字符串替换，任一侧为 null 时返回原串。
     *
     * @param text        原串
     * @param searchFor   被替换内容
     * @param replacement 替换为
     * @return 替换结果
     */
    public static String replace(String text, String searchFor, String replacement) {
        if (text == null || searchFor == null || searchFor.isEmpty()) {
            return text == null ? "" : text;
        }
        return text.replace(searchFor, replacement == null ? "" : replacement);
    }

    /**
     * 按分隔符切分成列表，忽略空片段。
     *
     * @param text      原串
     * @param separator 分隔符
     * @return 切分结果，永不返回 null
     */
    public static List<String> split(String text, String separator) {
        List<String> list = new ArrayList<>();
        if (isEmpty(text)) {
            return list;
        }
        for (String part : text.split(Pattern.quote(separator))) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return list;
    }

    /**
     * 判断字符串是否为纯数字。
     *
     * <p>只接受十进制整数，不接受小数与正负号 —— 本方法用于识别
     * {@code MATERIAL:数字} 这类配置写法，那里的"数量"必须是整数。
     *
     * @param text 待判断字符串
     * @return true 表示是纯数字
     */
    public static boolean isNumeric(String text) {
        if (isEmpty(text)) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 求值一个算术表达式。
     *
     * <p>用于 {@code shopPrice}、{@code shopNumber}、{@code [money]} 这类配置，
     * 它们既可以写 {@code 100}，也可以写 {@code 100 * 1.5} 或 {@code %input% * 10}。
     *
     * <p>实现是一个标准的递归下降解析器，支持 {@code + - * /}、括号与一元负号。
     * 之所以不用 {@code ScriptEngine}，是因为 Nashorn 在 Java 15 之后已被移除，
     * 用了会让插件在高版本服务端上直接崩掉。
     *
     * <p>求值失败返回 <b>null 而非 0</b>：把失败当 0 会让一个写错的价格变成免费。
     *
     * @param expression 表达式
     * @return 求值结果；无法解析时返回 null
     */
    public static Double evaluate(String expression) {
        if (isEmpty(expression)) {
            return null;
        }
        try {
            Parser parser = new Parser(expression.replace(" ", ""));
            double value = parser.parseExpression();
            // 必须全部消费完才算合法，否则 "1 2" 会被当成 1
            if (parser.index != parser.text.length()) {
                return null;
            }
            return value;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 算术表达式解析器。
     *
     * <p>文法（优先级由低到高）：
     * <pre>
     *   expression := term (('+' | '-') term)*
     *   term       := factor (('*' | '/') factor)*
     *   factor     := '-' factor | '(' expression ')' | number
     * </pre>
     */
    private static final class Parser {

        /** 待解析文本（已去空格）。 */
        private final String text;

        /** 当前读取位置。 */
        private int index;

        Parser(String text) {
            this.text = text;
        }

        /** @return 整个表达式的值 */
        double parseExpression() {
            double value = parseTerm();
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == '+') {
                    index++;
                    value += parseTerm();
                } else if (c == '-') {
                    index++;
                    value -= parseTerm();
                } else {
                    break;
                }
            }
            return value;
        }

        /** @return 一个乘除项的值 */
        double parseTerm() {
            double value = parseFactor();
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == '*') {
                    index++;
                    value *= parseFactor();
                } else if (c == '/') {
                    index++;
                    double divisor = parseFactor();
                    if (divisor == 0D) {
                        throw new ArithmeticException("除零");
                    }
                    value /= divisor;
                } else {
                    break;
                }
            }
            return value;
        }

        /** @return 一个因子（数字、括号或一元负号）的值 */
        double parseFactor() {
            if (index >= text.length()) {
                throw new IllegalStateException("表达式意外结束");
            }
            char c = text.charAt(index);
            if (c == '-') {
                index++;
                return -parseFactor();
            }
            if (c == '+') {
                index++;
                return parseFactor();
            }
            if (c == '(') {
                index++;
                double value = parseExpression();
                if (index >= text.length() || text.charAt(index) != ')') {
                    throw new IllegalStateException("括号不匹配");
                }
                index++;
                return value;
            }

            int start = index;
            while (index < text.length()) {
                char ch = text.charAt(index);
                if (Character.isDigit(ch) || ch == '.') {
                    index++;
                } else {
                    break;
                }
            }
            if (start == index) {
                throw new IllegalStateException("位置 " + index + " 处期望数字");
            }
            return Double.parseDouble(text.substring(start, index));
        }
    }
}
