package cn.listudio.exmenu.constants;

import cn.listudio.exmenu.util.TextUtil;

/**
 * 命令/动作标签。
 *
 * <p>菜单按钮的 {@code commands} 里每一项形如 {@code [标签] 内容}。
 * 标签决定了这条动作如何执行 —— 以玩家身份、控制台身份、发消息、开菜单……
 *
 * <p><b>匹配规则是最长匹配，不是首次匹配。</b>这一点很关键：{@code [opPerformCommand]}
 * 的前缀与 {@code [op]} 相同，如果按声明顺序取第一个命中的标签，{@code [opPerformCommand]}
 * 会被 {@code [op]} 抢走，导致该标签完全不可用。因此 {@link #match} 会挑选所有
 * 前缀匹配里<b>最长</b>的那个。
 */
public enum CommandTypeEnum {

    /** 发消息给玩家。 */
    MESSAGE("message", "发送消息"),

    /** 以玩家身份执行命令（需要玩家有权限）。 */
    PLAYER("player", "玩家执行命令"),

    /** 以控制台身份执行命令（无需权限）。 */
    CONSOLE("console", "控制台执行命令"),

    /** 以 OP 身份执行命令，执行后立刻收回 OP。 */
    OP("op", "临时OP执行命令"),

    /** 以 OP 身份执行命令，但执行前会执行 {@code [opPerformCommand]} 指定的前置命令。 */
    OP_PERFORM_COMMAND("opPerformCommand", "前置命令后OP执行"),

    /** 以玩家身份执行命令，但绕过权限检查。 */
    PERFORM_COMMAND("performCommand", "强制玩家执行命令"),

    /** 在玩家所在位置执行命令（命令方块形式）。 */
    DISPATCH_COMMAND("dispatchCommand", "以玩家位置执行命令"),

    /** 关闭当前菜单。 */
    CLOSE("close", "关闭菜单"),

    /** 打开另一个菜单。 */
    OPEN("open", "打开菜单"),

    /** 以玩家身份发送一个标题。 */
    TITLE("title", "发送标题"),

    /** 播放音效。 */
    SOUND("sound", "播放音效"),

    /** 延迟若干 tick 后继续执行后续动作。 */
    DELAY("delay", "延迟执行"),

    /** 给玩家加钱。 */
    MONEY("money", "给予金钱"),

    /** 给玩家加点券。 */
    POINT("point", "给予点券"),

    /** 给玩家物品。 */
    ITEM("item", "给予物品"),

    /** 全服广播消息。 */
    BROADCAST("broadcast", "全服广播"),

    /** 发送动作栏消息。 */
    ACTIONBAR("actionbar", "动作栏消息"),

    /** 给予玩家药水效果。 */
    EFFECT("effect", "给予效果"),

    /** 回满玩家生命值。 */
    HEAL("heal", "回满生命"),

    /** 回满玩家饱食度。 */
    FEED("feed", "回满饱食度"),

    /** 给予玩家经验（等级或经验值）。 */
    EXP("exp", "给予经验"),

    /** 执行一段 Kether/原版命令（保留扩展位）。 */
    ACTION("action", "自定义动作");

    /** 方括号内的标签原文，比较时不区分大小写。 */
    private final String tag;

    /** 中文说明，用于帮助信息。 */
    private final String description;

    CommandTypeEnum(String tag, String description) {
        this.tag = tag;
        this.description = description;
    }

    public String getTag() {
        return tag;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 解析一条动作字符串的类型。
     *
     * <p>采用<b>最长匹配</b>：在全部前缀匹配的枚举里取标签最长的那个。
     * 这解决了 {@code [op]} 抢占 {@code [opPerformCommand]} 的问题。
     *
     * <p>若没有任何标签匹配，返回 {@code null} —— 调用方应当把这条动作
     * 视为配置错误，而不是猜一个默认类型去执行。
     *
     * @param input 形如 {@code [message] 你好} 的动作字符串
     * @return 匹配到的枚举；无匹配或入参为空时返回 null
     */
    public static CommandTypeEnum match(String input) {
        if (TextUtil.isEmpty(input)) {
            return null;
        }

        CommandTypeEnum best = null;
        int bestLength = -1;

        for (CommandTypeEnum type : values()) {
            String prefix = "[" + type.tag + "]";
            if (input.regionMatches(true, 0, prefix, 0, prefix.length())) {
                // 取最长标签；长度相同时保留枚举声明顺序中靠前的那个
                if (type.tag.length() > bestLength) {
                    best = type;
                    bestLength = type.tag.length();
                }
            }
        }

        return best;
    }

    /**
     * 去掉动作字符串开头的标签，返回实际内容。
     *
     * @param input 形如 {@code [message] 你好} 的动作字符串
     * @return 标签后的内容，已 trim；无法解析时返回原串的 trim 结果
     */
    public static String stripTag(String input) {
        if (TextUtil.isEmpty(input)) {
            return "";
        }
        CommandTypeEnum type = match(input);
        if (type == null) {
            return input.trim();
        }
        return input.substring(("[" + type.tag + "]").length()).trim();
    }
}
