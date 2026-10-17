package cn.listudio.exmenu.constants;

/**
 * 输入类型。
 *
 * <p>菜单按钮可以要求玩家在聊天栏输入一个值，该值随后作为 {@code %input%}
 * 参与后续命令的变量替换。本枚举约束输入的合法性。
 *
 * <p>无论哪种类型，含 {@code %} 的输入一律拒绝 —— 否则玩家可以借此注入
 * PAPI 变量或命令占位符，造成越权。
 */
public enum InputTypeEnum {

    /** 任意文本，非空即可。 */
    STRING("任意文本"),

    /** 整数。 */
    NUMBER("整数"),

    /** 正数（大于 0）。 */
    POSITIVE_NUMBER("正数");

    private final String description;

    InputTypeEnum(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 按名称解析，不区分大小写。
     *
     * @param name 名称
     * @return 枚举，未匹配时返回 {@link #STRING}
     */
    public static InputTypeEnum parse(String name) {
        if (name == null || name.isEmpty()) {
            return STRING;
        }
        for (InputTypeEnum type : values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return STRING;
    }

    /**
     * 校验输入是否合法。
     *
     * @param input 玩家输入
     * @return true 表示合法
     */
    public boolean validate(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        switch (this) {
            case NUMBER:
                try {
                    Long.parseLong(input.trim());
                    return true;
                } catch (NumberFormatException e) {
                    return false;
                }
            case POSITIVE_NUMBER:
                try {
                    return Long.parseLong(input.trim()) > 0;
                } catch (NumberFormatException e) {
                    return false;
                }
            case STRING:
            default:
                return true;
        }
    }
}
