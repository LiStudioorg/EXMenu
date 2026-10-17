package cn.handyplus.menu.ng.constants;

/**
 * 界面类型。
 *
 * <p>用于标识一个 {@code HandyInventory} 实例属于哪一种界面，点击监听器据此
 * 分派到不同的处理逻辑。运行时状态容器也以此为键。
 */
public enum GuiTypeEnum {

    /** 菜单创建界面（{@code /plm create}）：列出可选的槽位布局。 */
    CREATE("create"),

    /** 菜单查看界面（{@code /plm view}）：以管理员视角预览一个菜单。 */
    VIEW("view"),

    /** 普通菜单界面：玩家使用的菜单。 */
    MENU("menu"),

    /** 物品库界面（{@code /plm item}）：浏览、取出、删除物品库条目。 */
    ITEM("item"),

    /** 二次确认界面：危险操作前的确认框。 */
    CONFIRM("confirm");

    private final String type;

    GuiTypeEnum(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }

    /**
     * 按字符串解析。
     *
     * @param type 类型字符串
     * @return 枚举；未匹配时返回 null
     */
    public static GuiTypeEnum parse(String type) {
        if (type == null) {
            return null;
        }
        for (GuiTypeEnum value : values()) {
            if (value.type.equalsIgnoreCase(type)) {
                return value;
            }
        }
        return null;
    }
}
