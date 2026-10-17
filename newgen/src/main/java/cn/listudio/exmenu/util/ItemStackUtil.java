package cn.listudio.exmenu.util;

import com.cryptomorin.xseries.XMaterial;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 物品构建工具。
 *
 * <p>这是 HandyLib {@code ItemStackUtil} / {@code ItemCompatUtil} 的替代实现。
 * 负责把配置里的字符串材质名解析成 {@link ItemStack}，并装配名称、Lore、
 * 附魔光效、自定义模型、头颅皮肤等。
 *
 * <p>材质解析分三级，与 PlayerMenu 的行为保持一致：
 * <ol>
 *   <li>先按 {@code MATERIAL} 原名直查 {@link Material}（1.13+ 的现代名称）</li>
 *   <li>交给 XSeries 的 {@link XMaterial#matchXMaterial(String)}，它内置了跨版本名称表，
 *       能处理 1.8 的 {@code WOOL:14} 这类"材质:数据值"写法</li>
 *   <li>仍失败则返回 null，由调用方决定回退策略</li>
 * </ol>
 */
public final class ItemStackUtil {

    /** 服务端是否支持自定义模型数据（1.14+）。 */
    private static final boolean CUSTOM_MODEL_SUPPORTED = hasMethod(Material.class, "isItem");

    /** 服务端是否支持隐藏工具提示（1.20.5+）。 */
    private static final boolean TOOLTIP_SUPPORTED = hasEnumConstant("HIDE_ADDITIONAL_TOOLTIP");

    /** 物品库序列化用的 Gson。禁用 HTML 转义，避免材质名里的 &lt; &gt; 被写成 unicode 转义。 */
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private ItemStackUtil() {
    }

    /**
     * 判断某方法在给定类上是否存在。
     *
     * @param owner      目标类
     * @param methodName 方法名
     * @return true 表示存在
     */
    private static boolean hasMethod(Class<?> owner, String methodName) {
        for (java.lang.reflect.Method method : owner.getMethods()) {
            if (method.getName().equals(methodName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断 {@link org.bukkit.inventory.ItemFlag} 是否含指定常量。
     *
     * @param name 常量名
     * @return true 表示存在
     */
    private static boolean hasEnumConstant(String name) {
        try {
            org.bukkit.inventory.ItemFlag.valueOf(name);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /**
     * 解析材质字符串为物品。
     *
     * <p>不会装配任何 meta —— 名称、Lore 等由 {@link #build} 完成。这样调用方可以
     * 先拿到"基础物品"，再决定要覆盖哪些属性。
     *
     * @param material 材质字符串，例如 {@code DIAMOND_SWORD}、{@code WOOL:14}
     * @return 单个物品；无法解析时返回 null
     */
    public static ItemStack parse(String material) {
        if (TextUtil.isEmpty(material)) {
            return null;
        }
        String name = material.trim();

        // 1) 现代材质名直查
        Material direct = Material.matchMaterial(name);
        if (direct != null && direct != Material.AIR && direct.isItem()) {
            return new ItemStack(direct);
        }

        // 2) XSeries 跨版本表
        Optional<XMaterial> matched = XMaterial.matchXMaterial(name);
        if (matched.isPresent()) {
            ItemStack item = matched.get().parseItem();
            if (item != null && item.getType() != Material.AIR) {
                return item;
            }
        }

        return null;
    }

    /**
     * 按配置装配一个物品。
     *
     * @param material           材质字符串
     * @param name               显示名称，可为 null 表示不改名
     * @param lore               Lore 列表，可为 null 或空
     * @param enchant            是否附加附魔光效
     * @param customModelData    自定义模型数据，0 表示不设置
     * @param hideFlag           是否隐藏属性标记（附魔、属性、可破坏等）
     * @param unbreakable        是否不可破坏，null 表示不修改
     * @param tooltipStyle       工具提示样式（1.21.2+），空表示不设置
     * @return 装配好的物品；材质无法解析时返回 null
     */
    public static ItemStack build(String material, String name, List<String> lore, boolean enchant,
                                  int customModelData, boolean hideFlag, Boolean unbreakable,
                                  String tooltipStyle) {
        ItemStack item = parse(material);
        if (item == null) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        if (TextUtil.isNotEmpty(name)) {
            meta.setDisplayName(TextUtil.color(name));
        }
        if (lore != null && !lore.isEmpty()) {
            meta.setLore(TextUtil.color(lore));
        }
        if (enchant) {
            // 只为视觉效果：加一个无害的附魔，配合 HIDE_ENCHANTS 让物品呈现附魔光效。
            // 用 UNBREAKING（旧名 DURABILITY）而不是直接改属性，因为它对所有物品都合法，
            // 且对实际玩法几乎无影响 —— 这是菜单物品的通行做法。
            meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
        }
        if (customModelData > 0 && CUSTOM_MODEL_SUPPORTED) {
            meta.setCustomModelData(customModelData);
        }
        if (unbreakable != null) {
            meta.setUnbreakable(unbreakable);
        }
        if (TextUtil.isNotEmpty(tooltipStyle) && TOOLTIP_SUPPORTED) {
            applyTooltipStyle(meta, tooltipStyle);
        }

        applyHideFlags(meta, hideFlag, enchant);

        item.setItemMeta(meta);
        return item;
    }

    /**
     * 把外观套用到已有的物品上。
     *
     * <p>与 {@link #build} 的区别：本方法不解析材质，而是修改传入的物品。
     * 用于物品库里的条目 —— 那些物品可能是从磁盘反序列化出来的复杂物品
     * （带 NBT、附魔、自定义名称），重新按材质构建会丢掉全部细节。
     *
     * @param item           已有物品
     * @param name           显示名，空表示不修改
     * @param lore           描述，空表示不修改
     * @param enchant        是否附加附魔光效
     * @param customModelData 自定义模型数据，0 表示不设置
     * @param hideFlag       是否隐藏属性标记
     * @param unbreakable    是否不可破坏，null 表示不修改
     * @param tooltipStyle   工具提示样式，空表示不设置
     * @param amount         数量，小于 1 时保持原数量
     * @return 处理后的物品
     */
    public static ItemStack apply(ItemStack item, String name, List<String> lore, boolean enchant,
                                  int customModelData, boolean hideFlag, Boolean unbreakable,
                                  String tooltipStyle, int amount) {
        if (item == null) {
            return null;
        }
        ItemStack result = item.clone();
        if (amount > 0) {
            result.setAmount(Math.min(result.getMaxStackSize(), amount));
        }

        ItemMeta meta = result.getItemMeta();
        if (meta == null) {
            return result;
        }

        if (TextUtil.isNotEmpty(name)) {
            meta.setDisplayName(TextUtil.color(name));
        }
        if (lore != null && !lore.isEmpty()) {
            meta.setLore(TextUtil.color(lore));
        }
        if (enchant) {
            meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
        }
        if (customModelData > 0 && CUSTOM_MODEL_SUPPORTED) {
            meta.setCustomModelData(customModelData);
        }
        if (unbreakable != null) {
            meta.setUnbreakable(unbreakable);
        }
        if (TextUtil.isNotEmpty(tooltipStyle) && TOOLTIP_SUPPORTED) {
            applyTooltipStyle(meta, tooltipStyle);
        }

        applyHideFlags(meta, hideFlag, enchant);
        result.setItemMeta(meta);
        return result;
    }

    /**
     * 把物品序列化为 JSON 字符串。
     *
     * <p>用 Bukkit 自带的 {@code ItemStack.serialize()}（一个 Map）再交给
     * Gson 转字符串，而不是 Java 原生序列化。原因有两个：
     * <ul>
     *   <li>原生序列化的字节流对格式变化极其敏感，跨版本升级容易反序列化失败</li>
     *   <li>Map 形式可读，出问题时能直接在数据库里看出来是什么物品</li>
     * </ul>
     *
     * <p>原版 PlayerMenu 用原生序列化 + Base64，这在 1.13 扁平化之后
     * 造成过大量"物品库读不出来"的问题。
     *
     * @param item 物品
     * @return JSON 字符串；序列化失败时返回 null
     */
    public static String serialize(ItemStack item) {
        if (item == null) {
            return null;
        }
        try {
            Map<String, Object> map = item.serialize();
            return GSON.toJson(map);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 从 JSON 字符串反序列化物品。
     *
     * @param json JSON 字符串
     * @return 物品；解析失败时返回 null
     */
    @SuppressWarnings("unchecked")
    public static ItemStack deserialize(String json) {
        if (TextUtil.isEmpty(json)) {
            return null;
        }
        try {
            Map<String, Object> map = GSON.fromJson(json, Map.class);
            if (map == null) {
                return null;
            }
            return ItemStack.deserialize(map);
        } catch (RuntimeException e) {
            // 单个物品读不出来不应让整个菜单崩掉，跳过即可
            return null;
        }
    }

    /**
     * 应用隐藏标记。
     *
     * @param meta      物品 meta
     * @param hideFlag  是否隐藏全部属性标记
     * @param enchant   是否因附魔光效而需要隐藏附魔行
     */
    private static void applyHideFlags(ItemMeta meta, boolean hideFlag, boolean enchant) {
        if (!hideFlag && !enchant) {
            return;
        }
        for (org.bukkit.inventory.ItemFlag flag : org.bukkit.inventory.ItemFlag.values()) {
            if (hideFlag) {
                meta.addItemFlags(flag);
            } else if (flag == org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS) {
                // 只隐藏附魔行，保留其它属性显示
                meta.addItemFlags(flag);
            }
        }
    }

    /**
     * 设置工具提示样式（1.21.2+ 的新属性）。
     *
     * <p>用反射调用，因为该 API 在旧版本不存在。失败时静默忽略。
     *
     * @param meta         物品 meta
     * @param tooltipStyle 样式名，例如 {@code minecraft:hidden}
     */
    private static void applyTooltipStyle(ItemMeta meta, String tooltipStyle) {
        try {
            Object key = org.bukkit.NamespacedKey.fromString(tooltipStyle);
            if (key == null) {
                return;
            }
            java.lang.reflect.Method method = meta.getClass().getMethod("setTooltipStyle", org.bukkit.NamespacedKey.class);
            method.invoke(meta, key);
        } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
            // 旧版本服务端不支持，忽略
        }
    }

    /**
     * 设置头颅皮肤。
     *
     * <p>支持两种写法：
     * <ul>
     *   <li>玩家名 —— 直接调用 {@code setOwningPlayer}</li>
     *   <li>{@code base64:...} 或纯 base64 纹理值 —— 通过反射设置 profile，避免编译期依赖
     *       com.mojang.authlib</li>
     * </ul>
     *
     * @param item  目标物品，必须是玩家头颅
     * @param owner 玩家名或 base64 纹理
     * @return 处理后的物品；非头颅或失败时原样返回
     */
    public static ItemStack applyHeadTexture(ItemStack item, String owner) {
        if (item == null || TextUtil.isEmpty(owner)) {
            return item;
        }
        if (!(item.getItemMeta() instanceof SkullMeta)) {
            return item;
        }
        SkullMeta skullMeta = (SkullMeta) item.getItemMeta();
        if (skullMeta == null) {
            return item;
        }

        String value = owner.startsWith("base64:") ? owner.substring("base64:".length()) : owner;
        if (looksLikeBase64(value)) {
            applyBase64Texture(skullMeta, value);
        } else {
            // 玩家名：离线服务器也能用，会按名字生成皮肤
            skullMeta.setOwner(value);
        }

        item.setItemMeta(skullMeta);
        return item;
    }

    /**
     * 粗略判断字符串是否像 base64 纹理值。
     *
     * @param value 待检查字符串
     * @return true 表示像 base64
     */
    private static boolean looksLikeBase64(String value) {
        return value.length() > 64 && value.matches("[A-Za-z0-9+/=]+");
    }

    /**
     * 通过 GameProfile 设置 base64 纹理。
     *
     * <p>用反射而非直接 import，因为 authlib 的包路径在个别服务端分支上会变。
     *
     * @param meta  头颅 meta
     * @param value base64 纹理值
     */
    private static void applyBase64Texture(SkullMeta meta, String value) {
        try {
            Class<?> profileClass = Class.forName("com.mojang.authlib.GameProfile");
            Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");

            Object profile = profileClass.getConstructor(UUID.class, String.class)
                    .newInstance(UUID.randomUUID(), "EXMenu");
            Object property = propertyClass.getConstructor(String.class, String.class)
                    .newInstance("textures", value);

            java.lang.reflect.Method getProperties = profileClass.getMethod("getProperties");
            Object propertyMap = getProperties.invoke(profile);
            java.lang.reflect.Method put = propertyMap.getClass().getMethod("put", Object.class, Object.class);
            put.invoke(propertyMap, "textures", property);

            java.lang.reflect.Method setProfile = meta.getClass().getMethod("setProfile", profileClass);
            setProfile.invoke(meta, profile);
        } catch (ReflectiveOperationException ignored) {
            // 无法设置纹理时保留默认头颅，不影响菜单可用性
        }
    }

    /**
     * 把物品安全地给予玩家，背包满时掉落在地上。
     *
     * <p>必须在拥有该玩家的线程上调用。
     *
     * @param player 目标玩家
     * @param item   物品
     * @param amount 数量，小于 1 时按 1 处理
     */
    public static void give(org.bukkit.entity.Player player, ItemStack item, int amount) {
        if (player == null || item == null || item.getType() == Material.AIR) {
            return;
        }
        int count = Math.max(1, amount);
        ItemStack copy = item.clone();
        copy.setAmount(Math.min(count, copy.getMaxStackSize()));

        int remaining = count;
        while (remaining > 0) {
            ItemStack batch = copy.clone();
            batch.setAmount(Math.min(remaining, copy.getMaxStackSize()));
            java.util.Map<Integer, ItemStack> leftover = player.getInventory().addItem(batch);
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
            remaining -= batch.getAmount();
        }
    }

    /**
     * 安全取物品显示名，无自定义名称时回退为材质名。
     *
     * @param item 物品
     * @return 显示名，物品为 null 时返回空串
     */
    public static String displayName(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return "";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.getDisplayName();
        }
        return item.getType().name();
    }

    /**
     * 插件初始化钩子。
     *
     * <p>XSeries 在某些服务端上需要预热版本探测表。调用一次即可，重复调用无副作用。
     * 保留此方法是为了让启动流程显式表达"物品兼容层已就绪"。
     */
    public static void init() {
        // 触发 XMaterial 静态初始化，提前完成版本探测，
        // 避免首次开菜单时才做这步而产生卡顿。
        XMaterial.matchXMaterial("STONE");
        Bukkit.getLogger().fine("[EXMenu] ItemStackUtil initialized.");
    }
}
