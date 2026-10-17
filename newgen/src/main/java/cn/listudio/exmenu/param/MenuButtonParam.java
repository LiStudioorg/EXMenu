package cn.listudio.exmenu.param;

import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个菜单按钮的全部属性。
 *
 * <p>这是配置文件与运行时代码之间的唯一契约：YAML 里每个 {@code slots} 条目
 * 都被解析成本类的一个实例，之后所有渲染、检查、执行逻辑都只读这个对象，
 * 不再回头看 YAML。这样做的好处是渲染路径上完全没有配置解析开销。
 *
 * <p>字段分五组：<b>外观</b>、<b>动作</b>、<b>条件</b>、<b>限制</b>、<b>商店</b>。
 */
public class MenuButtonParam {

    // ------------------------------------------------------------------
    // 外观
    // ------------------------------------------------------------------

    /** 材质。支持原版名、{@code MATERIAL:data}、CE/IA 自定义写法。 */
    private String material;

    /** 显示名称，支持颜色代码与 PAPI 变量。 */
    private String name;

    /** Lore。 */
    private List<String> lore = new ArrayList<>();

    /** 所在槽位列表。一个按钮可以占多个槽位。 */
    private List<Integer> slots = new ArrayList<>();

    /** 该按钮所属的菜单文件名。 */
    private String menuName;

    /** 动态名称的 PAPI 表达式；非空时每次渲染都重新求值。 */
    private String dynamicName;

    /** 动态 Lore 的 PAPI 表达式列表。 */
    private List<String> dynamicLore = new ArrayList<>();

    /** 动态数量：数字，或用 PAPI 表达式算出数字。 */
    private String dynamicAmount;

    /** 物品数量。 */
    private int amount = 1;

    /** 是否为 AIR（占位/清空槽位）。 */
    private boolean air;

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------

    /** 左键执行的命令/动作链。 */
    private List<String> commands = new ArrayList<>();

    /** 取该按钮的 {@code id}。id 同时是物品库引用与限制标识。 */
    private int id;

    // ------------------------------------------------------------------
    // 条件
    // ------------------------------------------------------------------

    /** 需要的权限，全部满足才显示。 */
    private List<String> permission = new ArrayList<>();

    /** 排斥的权限，任一命中则隐藏。 */
    private List<String> notPermission = new ArrayList<>();

    /** 显示条件，全部满足才显示。格式见 {@code checkCondition}。 */
    private List<String> conditions = new ArrayList<>();

    /** 隐藏条件，任一命中则隐藏。 */
    private List<String> conditionsHide = new ArrayList<>();

    // ------------------------------------------------------------------
    // 限制
    // ------------------------------------------------------------------

    /** 冷却时间，毫秒。0 表示无冷却。 */
    private long cd;

    /** 冷却期间是否直接隐藏按钮。 */
    private boolean cdHide;

    /** 冷却提示文本。 */
    private String cdMsg;

    /** 次数限制。0 表示无限制。 */
    private int limit;

    /** 次数用尽后是否隐藏按钮。 */
    private boolean limitHide;

    /** 次数用尽提示文本。 */
    private String limitMsg;

    // ------------------------------------------------------------------
    // 商店
    // ------------------------------------------------------------------

    /** 商店类型：{@code leftBuyRightSell} / {@code leftSellRightBuy} / {@code buy} / {@code sell}。 */
    private String shopType;

    /** 售价/买价，支持公式。 */
    private String shopPrice;

    /** 交易数量表达式。 */
    private String shopNumber;

    /** 购买时给予的材质（{@code 材质:数量}）。 */
    private String shopGiveMaterial;

    /** 出售时回收的材质（{@code 材质:数量}）。 */
    private String shopMaterial;

    /** 经济类型：{@code money} / {@code point}。 */
    private String shopEconomy;

    // ------------------------------------------------------------------
    // 交互与外观开关
    // ------------------------------------------------------------------

    /** 是否附加附魔光效。 */
    private boolean enchant;

    /** 是否隐藏属性标记。默认 true。 */
    private boolean hideFlag = true;

    /** 是否隐藏附魔行。默认 true。 */
    private boolean hideEnchant = true;

    /** 是否不可破坏，null 表示不修改。 */
    private Boolean unbreakable;

    /** 自定义模型数据。 */
    private int customModelData;

    /** 工具提示样式（1.21.2+）。 */
    private String tooltipStyle;

    /** 头颅拥有者：玩家名或 base64 纹理。 */
    private String head;

    /** 头颅 base64 纹理的备用字段。 */
    private String headBase;

    /** 是否需要二次确认。 */
    private boolean confirm;

    /** 点击后是否关闭菜单。 */
    private boolean close;

    /** 是否需要玩家输入。输入类型见 {@code InputTypeEnum}。 */
    private String input;

    /** 输入类型枚举名。 */
    private String inputType;

    /** 点击时该按钮的动作类型：{@code left} / {@code right} / {@code middle}。 */
    private List<String> actionsLeft = new ArrayList<>();

    /** 右键专属动作。 */
    private List<String> actionsRight = new ArrayList<>();

    /** 中键专属动作。 */
    private List<String> actionsMiddle = new ArrayList<>();

    /** 打开的目标菜单（{@code openItem} 的逆向：点击后跳转）。 */
    private String openItem;

    /** 优先级，同槽位冲突时数值大的胜出。 */
    private int priority;

    /** 点击音效，格式 {@code SOUND:音量:音调}。 */
    private String sound;

    /** 失败音效。为空时回退用 {@code sound}。 */
    private String failSound;

    /** 点击时由哪个点击类型触发，运行时注入。 */
    private ClickType eventClickType;

    /**
     * 玩家本次在聊天栏实际输入的值，运行时注入。
     *
     * <p>与 {@link #input} 的区别：{@code input} 是配置里的"输入声明"
     * （类型名或提示文本），本字段是玩家真正敲进去的内容，用于替换 {@code %input%}。
     *
     * <p>刻意做成按钮对象上的字段而不是 {@code ThreadLocal}：输入流程会跨越
     * 聊天事件线程与实体线程，{@code ThreadLocal} 写在一个线程、读在另一个线程，
     * 值根本传不过去。
     */
    private String inputValue;

    /**
     * 本次点击是否已经用原子门槛占用了次数名额，运行时注入。
     *
     * <p>点击路径会先用 {@code WHERE number < limit} 的条件更新抢占名额；
     * 抢到了就置位此标记，后续的记录阶段便不再重复累加，
     * 避免一次点击被记成两次。
     */
    private boolean numberClaimed;

    /** 是否为动态按钮（每次渲染重新解析 PAPI）。 */
    private boolean dynamic;

    /** 原始的按钮配置节，供扩展使用。 */
    private Map<String, Object> raw = new HashMap<>();

    public MenuButtonParam() {
    }

    // ------------------------------------------------------------------
    // getter / setter
    // ------------------------------------------------------------------

    public String getMaterial() {
        return material;
    }

    public void setMaterial(String material) {
        this.material = material;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getLore() {
        return lore;
    }

    public void setLore(List<String> lore) {
        this.lore = lore == null ? new ArrayList<>() : lore;
    }

    public List<Integer> getSlots() {
        return slots;
    }

    public void setSlots(List<Integer> slots) {
        this.slots = slots == null ? new ArrayList<>() : slots;
    }

    public String getMenuName() {
        return menuName;
    }

    public void setMenuName(String menuName) {
        this.menuName = menuName;
    }

    public String getDynamicName() {
        return dynamicName;
    }

    public void setDynamicName(String dynamicName) {
        this.dynamicName = dynamicName;
    }

    public List<String> getDynamicLore() {
        return dynamicLore;
    }

    public void setDynamicLore(List<String> dynamicLore) {
        this.dynamicLore = dynamicLore == null ? new ArrayList<>() : dynamicLore;
    }

    public String getDynamicAmount() {
        return dynamicAmount;
    }

    public void setDynamicAmount(String dynamicAmount) {
        this.dynamicAmount = dynamicAmount;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }

    public boolean isAir() {
        return air;
    }

    public void setAir(boolean air) {
        this.air = air;
    }

    public List<String> getCommands() {
        return commands;
    }

    public void setCommands(List<String> commands) {
        this.commands = commands == null ? new ArrayList<>() : commands;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public List<String> getPermission() {
        return permission;
    }

    public void setPermission(List<String> permission) {
        this.permission = permission == null ? new ArrayList<>() : permission;
    }

    public List<String> getNotPermission() {
        return notPermission;
    }

    public void setNotPermission(List<String> notPermission) {
        this.notPermission = notPermission == null ? new ArrayList<>() : notPermission;
    }

    public List<String> getConditions() {
        return conditions;
    }

    public void setConditions(List<String> conditions) {
        this.conditions = conditions == null ? new ArrayList<>() : conditions;
    }

    public List<String> getConditionsHide() {
        return conditionsHide;
    }

    public void setConditionsHide(List<String> conditionsHide) {
        this.conditionsHide = conditionsHide == null ? new ArrayList<>() : conditionsHide;
    }

    public long getCd() {
        return cd;
    }

    public void setCd(long cd) {
        this.cd = cd;
    }

    public boolean isCdHide() {
        return cdHide;
    }

    public void setCdHide(boolean cdHide) {
        this.cdHide = cdHide;
    }

    public String getCdMsg() {
        return cdMsg;
    }

    public void setCdMsg(String cdMsg) {
        this.cdMsg = cdMsg;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public boolean isLimitHide() {
        return limitHide;
    }

    public void setLimitHide(boolean limitHide) {
        this.limitHide = limitHide;
    }

    public String getLimitMsg() {
        return limitMsg;
    }

    public void setLimitMsg(String limitMsg) {
        this.limitMsg = limitMsg;
    }

    public String getShopType() {
        return shopType;
    }

    public void setShopType(String shopType) {
        this.shopType = shopType;
    }

    public String getShopPrice() {
        return shopPrice;
    }

    public void setShopPrice(String shopPrice) {
        this.shopPrice = shopPrice;
    }

    public String getShopNumber() {
        return shopNumber;
    }

    public void setShopNumber(String shopNumber) {
        this.shopNumber = shopNumber;
    }

    public String getShopGiveMaterial() {
        return shopGiveMaterial;
    }

    public void setShopGiveMaterial(String shopGiveMaterial) {
        this.shopGiveMaterial = shopGiveMaterial;
    }

    public String getShopMaterial() {
        return shopMaterial;
    }

    public void setShopMaterial(String shopMaterial) {
        this.shopMaterial = shopMaterial;
    }

    public String getShopEconomy() {
        return shopEconomy;
    }

    public void setShopEconomy(String shopEconomy) {
        this.shopEconomy = shopEconomy;
    }

    public boolean isEnchant() {
        return enchant;
    }

    public void setEnchant(boolean enchant) {
        this.enchant = enchant;
    }

    public boolean isHideFlag() {
        return hideFlag;
    }

    public void setHideFlag(boolean hideFlag) {
        this.hideFlag = hideFlag;
    }

    public boolean isHideEnchant() {
        return hideEnchant;
    }

    public void setHideEnchant(boolean hideEnchant) {
        this.hideEnchant = hideEnchant;
    }

    public Boolean getUnbreakable() {
        return unbreakable;
    }

    public void setUnbreakable(Boolean unbreakable) {
        this.unbreakable = unbreakable;
    }

    public int getCustomModelData() {
        return customModelData;
    }

    public void setCustomModelData(int customModelData) {
        this.customModelData = customModelData;
    }

    public String getTooltipStyle() {
        return tooltipStyle;
    }

    public void setTooltipStyle(String tooltipStyle) {
        this.tooltipStyle = tooltipStyle;
    }

    public String getHead() {
        return head;
    }

    public void setHead(String head) {
        this.head = head;
    }

    public String getHeadBase() {
        return headBase;
    }

    public void setHeadBase(String headBase) {
        this.headBase = headBase;
    }

    public boolean isConfirm() {
        return confirm;
    }

    public void setConfirm(boolean confirm) {
        this.confirm = confirm;
    }

    public boolean isClose() {
        return close;
    }

    public void setClose(boolean close) {
        this.close = close;
    }

    public String getInput() {
        return input;
    }

    public void setInput(String input) {
        this.input = input;
    }

    public String getInputType() {
        return inputType;
    }

    public void setInputType(String inputType) {
        this.inputType = inputType;
    }

    /** 取玩家本次输入的值；未输入过时返回空串（永不为 null）。 */
    public String getInputValue() {
        return inputValue == null ? "" : inputValue;
    }

    public void setInputValue(String inputValue) {
        this.inputValue = inputValue;
    }

    /** 本次点击是否已通过原子门槛占用次数名额。 */
    public boolean isNumberClaimed() {
        return numberClaimed;
    }

    public void setNumberClaimed(boolean numberClaimed) {
        this.numberClaimed = numberClaimed;
    }

    public List<String> getActionsLeft() {
        return actionsLeft;
    }

    public void setActionsLeft(List<String> actionsLeft) {
        this.actionsLeft = actionsLeft == null ? new ArrayList<>() : actionsLeft;
    }

    public List<String> getActionsRight() {
        return actionsRight;
    }

    public void setActionsRight(List<String> actionsRight) {
        this.actionsRight = actionsRight == null ? new ArrayList<>() : actionsRight;
    }

    public List<String> getActionsMiddle() {
        return actionsMiddle;
    }

    public void setActionsMiddle(List<String> actionsMiddle) {
        this.actionsMiddle = actionsMiddle == null ? new ArrayList<>() : actionsMiddle;
    }

    public String getOpenItem() {
        return openItem;
    }

    public void setOpenItem(String openItem) {
        this.openItem = openItem;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public String getSound() {
        return sound;
    }

    public void setSound(String sound) {
        this.sound = sound;
    }

    public String getFailSound() {
        return failSound;
    }

    public void setFailSound(String failSound) {
        this.failSound = failSound;
    }

    public ClickType getEventClickType() {
        return eventClickType;
    }

    public void setEventClickType(ClickType eventClickType) {
        this.eventClickType = eventClickType;
    }

    public boolean isDynamic() {
        return dynamic;
    }

    public void setDynamic(boolean dynamic) {
        this.dynamic = dynamic;
    }

    public Map<String, Object> getRaw() {
        return raw;
    }

    public void setRaw(Map<String, Object> raw) {
        this.raw = raw == null ? new HashMap<>() : raw;
    }

    /**
     * 是否有任何限制配置。
     *
     * <p>{@code id == 0} 时一律返回 false —— 这是 id 双语义的关键：
     * id 为 0 的按钮不参与冷却与次数记录，配置里的 cd/limit 会被忽略。
     *
     * @return true 表示需要读写限制记录
     */
    public boolean hasLimit() {
        return id != 0 && (cd > 0 || limit > 0 || cdHide || limitHide);
    }

    /**
     * 该按钮是否需要落库（用于决定是否参与批量查询）。
     *
     * @return true 表示 {@code id != 0}
     */
    public boolean isPersistent() {
        return id != 0;
    }

    /**
     * 取实际生效的失败音效。
     *
     * @return 失败音效字符串，可能为空
     */
    public String resolveFailSound() {
        return failSound == null || failSound.isEmpty() ? sound : failSound;
    }

    @Override
    public String toString() {
        return "MenuButtonParam{menu='" + menuName + "', id=" + id + ", material='" + material
                + "', slots=" + slots + "}";
    }

    /**
     * 复制一份按钮。
     *
     * <p>点击时必须复制，不能直接改缓存对象上的 {@code eventClickType}。
     * 缓存里的按钮是所有玩家、所有点击共享的同一份实例，一旦被写入
     * 某次点击的类型，后续所有点击都会读到那个残留值 —— 表现为
     * "左键点过一次之后，右键也执行左键动作"。
     *
     * <p>列表字段做浅拷贝即可：列表内容在解析后就不再变化，只有
     * {@code eventClickType} 这类"每次点击都不同"的标量需要隔离。
     *
     * @return 新实例
     */
    public MenuButtonParam copy() {
        MenuButtonParam copy = new MenuButtonParam();
        copy.menuName = menuName;
        copy.material = material;
        copy.name = name;
        copy.lore = lore == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(lore);
        copy.slots = slots == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(slots);
        copy.dynamicName = dynamicName;
        copy.dynamicLore = dynamicLore == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(dynamicLore);
        copy.dynamicAmount = dynamicAmount;
        copy.amount = amount;
        copy.air = air;

        copy.commands = commands == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(commands);
        copy.id = id;
        copy.close = close;
        copy.openItem = openItem;
        copy.sound = sound;
        copy.failSound = failSound;
        copy.priority = priority;
        copy.actionsLeft = actionsLeft == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(actionsLeft);
        copy.actionsRight = actionsRight == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(actionsRight);
        copy.actionsMiddle = actionsMiddle == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(actionsMiddle);

        copy.permission = permission == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(permission);
        copy.notPermission = notPermission == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(notPermission);
        copy.conditions = conditions == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(conditions);
        copy.conditionsHide = conditionsHide == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(conditionsHide);

        copy.cd = cd;
        copy.cdHide = cdHide;
        copy.cdMsg = cdMsg;
        copy.limit = limit;
        copy.limitHide = limitHide;
        copy.limitMsg = limitMsg;

        copy.shopType = shopType;
        copy.shopPrice = shopPrice;
        copy.shopNumber = shopNumber;
        copy.shopGiveMaterial = shopGiveMaterial;
        copy.shopMaterial = shopMaterial;
        copy.shopEconomy = shopEconomy;

        copy.enchant = enchant;
        copy.hideFlag = hideFlag;
        copy.hideEnchant = hideEnchant;
        copy.unbreakable = unbreakable;
        copy.customModelData = customModelData;
        copy.tooltipStyle = tooltipStyle;
        copy.head = head;
        copy.headBase = headBase;
        copy.confirm = confirm;
        copy.input = input;
        copy.inputType = inputType;
        copy.inputValue = inputValue;
        copy.numberClaimed = numberClaimed;
        copy.dynamic = dynamic;
        // raw 是只读的解析结果，共享同一份即可
        copy.raw = raw;
        // eventClickType 刻意不复制 —— 它由本次点击单独注入
        return copy;
    }
}
