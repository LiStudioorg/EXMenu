package cn.listudio.exmenu.inventory;

import cn.listudio.exmenu.constants.InputTypeEnum;
import cn.listudio.exmenu.param.MenuButtonParam;
import cn.listudio.exmenu.util.TextUtil;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * YAML 配置节到 {@link MenuButtonParam} 的解析器。
 *
 * <p>这是配置与代码之间唯一的转换点。解析一次，之后渲染循环里全是内存对象，
 * 不再触碰 YAML —— 菜单每次打开都要重建整个界面，把解析开销挪到加载期是必要的。
 *
 * <p>解析遵循<b>宽容原则</b>：单个字段写错不会让整个按钮失效，而是回退到默认值。
 * 但结构性错误（没有 slots、没有 material）会让按钮被丢弃，因为这样的按钮
 * 无论如何都无法渲染。
 */
public final class ButtonParser {

    private ButtonParser() {
    }

    /**
     * 解析一个按钮。
     *
     * @param section  按钮配置节
     * @param menuName 所属菜单文件名
     * @param index    按钮在菜单中的序号，仅用于错误提示
     * @return 按钮模型；结构非法时返回 null
     */
    public static MenuButtonParam parse(ConfigurationSection section, String menuName, int index) {
        if (section == null) {
            return null;
        }

        MenuButtonParam param = new MenuButtonParam();
        param.setMenuName(menuName);

        // ---- 槽位：多写成 slot: 1 或多写成 slots: [1,2,3] ----
        List<Integer> slots = new ArrayList<>();
        if (section.isList("slots")) {
            for (Object raw : section.getList("slots", new ArrayList<>())) {
                Integer slot = toSlot(raw);
                if (slot != null && slot >= 0) {
                    slots.add(slot);
                }
            }
        } else if (section.contains("slot")) {
            Integer slot = toSlot(section.get("slot"));
            if (slot != null && slot >= 0) {
                slots.add(slot);
            }
        }
        if (slots.isEmpty()) {
            // 没有槽位的按钮无法渲染，直接丢弃
            return null;
        }
        param.setSlots(slots);

        // ---- 材质 ----
        param.setMaterial(section.getString("material", "STONE"));
        param.setAir("AIR".equalsIgnoreCase(param.getMaterial()));

        // ---- 外观 ----
        param.setName(section.getString("name"));
        param.setLore(toStringList(section, "lore"));
        param.setDynamicName(section.getString("dynamicName"));
        param.setDynamicLore(toStringList(section, "dynamicLore"));
        param.setDynamicAmount(section.getString("dynamicAmount"));
        param.setAmount(Math.max(1, section.getInt("amount", 1)));
        param.setEnchant(section.getBoolean("enchant", false));
        param.setHideFlag(section.getBoolean("hideFlag", true));
        param.setHideEnchant(section.getBoolean("hideEnchant", true));
        param.setCustomModelData(section.getInt("customModelData", 0));
        param.setTooltipStyle(section.getString("tooltipStyle"));
        param.setHead(section.getString("head"));
        param.setHeadBase(section.getString("headBase"));
        if (section.contains("unbreakable")) {
            param.setUnbreakable(section.getBoolean("unbreakable"));
        }

        // ---- 动作 ----
        param.setCommands(toStringList(section, "commands"));
        param.setId(section.getInt("id", 0));
        param.setClose(section.getBoolean("close", false));
        param.setOpenItem(section.getString("openItem"));
        param.setSound(section.getString("sound"));
        param.setFailSound(section.getString("failSound"));
        param.setPriority(section.getInt("priority", 0));
        param.setActionsLeft(toStringList(section, "actions.left"));
        param.setActionsRight(toStringList(section, "actions.right"));
        param.setActionsMiddle(toStringList(section, "actions.middle"));

        // ---- 权限 ----
        param.setPermission(toStringList(section, "permission"));
        param.setNotPermission(toStringList(section, "notPermission"));

        // ---- 条件 ----
        param.setConditions(toStringList(section, "conditions"));
        param.setConditionsHide(toStringList(section, "conditionsHide"));

        // ---- 限制：cd 支持 "5s" / "1m" / 纯毫秒数字三种写法 ----
        param.setCd(parseDurationMillis(section, "cd"));
        param.setCdHide(section.getBoolean("cdHide", false));
        param.setCdMsg(section.getString("cdMsg"));
        param.setLimit(section.getInt("limit", 0));
        param.setLimitHide(section.getBoolean("limitHide", false));
        param.setLimitMsg(section.getString("limitMsg"));

        // ---- 商店 ----
        param.setShopType(section.getString("shopType"));
        param.setShopPrice(section.getString("shopPrice"));
        param.setShopNumber(section.getString("shopNumber"));
        param.setShopGiveMaterial(section.getString("shopGiveMaterial"));
        param.setShopMaterial(section.getString("shopMaterial"));
        param.setShopEconomy(section.getString("shopEconomy", "money"));

        // ---- 确认与输入 ----
        param.setConfirm(section.getBoolean("confirm", false));
        String input = section.getString("input");
        param.setInput(input);
        if (TextUtil.isNotEmpty(input)) {
            // input 既可以写类型名（NUMBER），也可以写提示文本；类型另用 inputType 指定
            InputTypeEnum inputType = InputTypeEnum.parse(
                    section.getString("inputType", input));
            param.setInputType(inputType.name());
        }

        // ---- 原始数据，供扩展读取 ----
        Map<String, Object> raw = new HashMap<>();
        for (String key : section.getKeys(true)) {
            raw.put(key, section.get(key));
        }
        param.setRaw(raw);

        // 动态标记：任一动态字段非空即需要每次渲染重新求值
        param.setDynamic(TextUtil.isNotEmpty(param.getDynamicName())
                || !param.getDynamicLore().isEmpty()
                || TextUtil.isNotEmpty(param.getDynamicAmount()));

        return param;
    }

    /**
     * 把配置值转成槽位数字。
     *
     * @param raw 原始值，可能是数字或数字字符串
     * @return 槽位号；无法解析时返回 null
     */
    private static Integer toSlot(Object raw) {
        if (raw instanceof Number) {
            return ((Number) raw).intValue();
        }
        if (raw == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 读取字符串列表，兼容单字符串写法。
     *
     * @param section 配置节
     * @param path    键路径
     * @return 列表，永不返回 null
     */
    private static List<String> toStringList(ConfigurationSection section, String path) {
        List<String> result = new ArrayList<>();
        Object raw = section.get(path);
        if (raw == null) {
            return result;
        }
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        result.add(String.valueOf(raw));
        return result;
    }

    /**
     * 解析冷却时长。
     *
     * <p>支持三种写法，全部换算为毫秒：
     * <ul>
     *   <li>{@code 5s} / {@code 5秒} —— 秒</li>
     *   <li>{@code 2m} / {@code 2分} —— 分</li>
     *   <li>{@code 3000} —— 直接当作毫秒</li>
     * </ul>
     *
     * <p>这个宽容度是必要的：原版 PlayerMenu 只接受裸数字毫秒，玩家写出 {@code 5s}
     * 会解析成 5 毫秒，冷却形同虚设且极难排查。
     *
     * @param section 配置节
     * @param path    键路径
     * @return 毫秒数，未配置或无法解析时返回 0
     */
    private static long parseDurationMillis(ConfigurationSection section, String path) {
        Object raw = section.get(path);
        if (raw == null) {
            return 0L;
        }
        if (raw instanceof Number) {
            return Math.max(0L, ((Number) raw).longValue());
        }

        String text = String.valueOf(raw).trim().toLowerCase();
        if (text.isEmpty()) {
            return 0L;
        }

        long multiplier = 1L;
        if (text.endsWith("s") || text.endsWith("秒")) {
            multiplier = 1000L;
            text = text.replace("秒", "").replace("s", "").trim();
        } else if (text.endsWith("m") || text.endsWith("分")) {
            multiplier = 60_000L;
            text = text.replace("分", "").replace("m", "").trim();
        } else if (text.endsWith("h") || text.endsWith("时")) {
            multiplier = 3_600_000L;
            text = text.replace("时", "").replace("h", "").trim();
        }

        try {
            return Math.max(0L, (long) (Double.parseDouble(text) * multiplier));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
