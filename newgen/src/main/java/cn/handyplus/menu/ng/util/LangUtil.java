package cn.handyplus.menu.ng.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 语言文件管理。
 *
 * <p>所有面向玩家的文本都从这里取。语言文件缺失时回退到内置的英文默认值，
 * 保证插件在任何情况下都能给玩家一个可读的提示，而不是抛异常或显示 {@code null}。
 *
 * <p>支持三种语言文件：{@code en_US.yml}、{@code zh_CN.yml}、{@code zh_TW.yml}。
 * {@code config.yml} 的 {@code language} 键决定用哪个。
 */
public final class LangUtil {

    /** 内置默认值：语言文件损坏或缺失时的最后防线。 */
    private static final Map<String, String> DEFAULTS = new HashMap<>();

    static {
        DEFAULTS.put("prefix", "&8[&bPlayerMenu&8] &r");
        DEFAULTS.put("noPermission", "&c你没有权限执行此操作");
        DEFAULTS.put("playerOnly", "&c该命令只能由玩家执行");
        DEFAULTS.put("noPlayerFailureMsg", "&c请指定一个在线玩家");
        DEFAULTS.put("playerNotOnline", "&c玩家不在线");
        DEFAULTS.put("reloadMsg", "&a配置已重载");
        DEFAULTS.put("reloadFailureMsg", "&c配置重载失败");
        DEFAULTS.put("noMenu", "&c菜单不存在");
        DEFAULTS.put("noTimeLimit", "&c请等待 &e%time% &c秒后再试");
        DEFAULTS.put("noItem", "&c你没有足够的物品");
        DEFAULTS.put("noNumber", "&c数量必须为数字");
        DEFAULTS.put("noPositiveNumber", "&c数量必须为正数");
        DEFAULTS.put("noBalance", "&c你的余额不足");
        DEFAULTS.put("noInput", "&c输入不能为空或包含 % 符号");
        DEFAULTS.put("inputExit", "&e输入 T 可退出输入");
        DEFAULTS.put("inputPrompt", "&e请在聊天栏输入内容");
        DEFAULTS.put("inputTimeout", "&c输入已取消");
        DEFAULTS.put("buyMsg", "&a购买成功，花费 &e%price%");
        DEFAULTS.put("sellMsg", "&a出售成功，获得 &e%price%");
        DEFAULTS.put("money", "金币");
        DEFAULTS.put("point", "点券");
        DEFAULTS.put("clickOperatorReason", "&c操作未完成");
        DEFAULTS.put("clearSucceedMsg", "&a已清空");
        DEFAULTS.put("addMenuItemMsg", "&a物品已加入物品库，id: &e%id%");
        DEFAULTS.put("getMenuItemMsg", "&a物品已取出");
        DEFAULTS.put("noLimitItem", "&c该物品无法购买");
        DEFAULTS.put("conditionFailure", "&c条件不满足");
        DEFAULTS.put("configError", "&c配置异常: %reason%");
        DEFAULTS.put("clockFailureMsg", "&c时钟物品功能未启用");
        DEFAULTS.put("noCommandPermission", "&c你没有权限使用该命令");
    }

    /** 当前语言的键值表。 */
    private static Map<String, String> messages = new HashMap<>(DEFAULTS);

    /** 消息前缀。 */
    private static String prefix = DEFAULTS.get("prefix");

    private LangUtil() {
    }

    /**
     * 加载语言文件。
     *
     * @param plugin   插件实例
     * @param dataFolder 插件数据目录
     * @param language 语言代码，例如 {@code zh_CN}
     */
    public static void load(Plugin plugin, File dataFolder, String language) {
        Map<String, String> loaded = new HashMap<>(DEFAULTS);

        // 优先读磁盘上的文件，其次读 jar 内的默认资源
        File file = new File(new File(dataFolder, "languages"), language + ".yml");
        YamlConfiguration config = file.exists()
                ? YamlUtil.load(file)
                : YamlUtil.loadResource(plugin, "languages/" + language + ".yml");

        for (String key : config.getKeys(false)) {
            Object value = config.get(key);
            if (value != null) {
                loaded.put(key, String.valueOf(value));
            }
        }

        messages = loaded;
        prefix = loaded.containsKey("prefix") ? loaded.get("prefix") : "";
    }

    /**
     * 取一条消息原文（未做颜色转换）。
     *
     * @param key 键名
     * @return 消息内容；键不存在时返回 {@code <key>} 便于定位配置缺失
     */
    public static String raw(String key) {
        if (key == null) {
            return "";
        }
        String value = messages.get(key);
        return value == null ? "<" + key + ">" : value;
    }

    /**
     * 取一条带前缀的、已做颜色转换的消息。
     *
     * @param key 键名
     * @return 可直接发送给玩家的文本
     */
    public static String message(String key) {
        return TextUtil.color(prefix + raw(key));
    }

    /**
     * 取一条不带前缀的、已做颜色转换的消息。
     *
     * @param key 键名
     * @return 可直接发送给玩家的文本
     */
    public static String plain(String key) {
        return TextUtil.color(raw(key));
    }

    /**
     * 取一条消息并做变量替换。
     *
     * <p>替换占位符形如 {@code %name%}。
     *
     * @param key    键名
     * @param pairs  键值对，按 {@code 占位符, 值, 占位符, 值...} 顺序传入
     * @return 替换后的文本
     */
    public static String format(String key, String... pairs) {
        String text = raw(key);
        if (pairs != null) {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                text = TextUtil.replace(text, pairs[i], pairs[i + 1]);
            }
        }
        return TextUtil.color(prefix + text);
    }

    /**
     * 取一条带前缀的消息并发送给命令发送者（玩家或控制台）。
     *
     * <p>命令处理里有大量"提示一句话就 return"的分支，先 {@link #message(String)}
     * 再 {@code sendMessage} 会把每个分支都写成两行。这个方法是那类分支的快捷方式，
     * 语义与 {@code sendMessage(message(key))} 完全一致。
     *
     * @param sender 接收者，为 null 时静默忽略（异步回调里 sender 可能已失效）
     * @param key    键名
     */
    public static void send(org.bukkit.command.CommandSender sender, String key) {
        if (sender == null) {
            return;
        }
        sender.sendMessage(message(key));
    }

    /**
     * 取一条带前缀的消息并发送给玩家。
     *
     * <p>与 {@link #send(org.bukkit.command.CommandSender, String)} 同义，
     * 只是把参数类型收窄到 {@link org.bukkit.entity.Player}，省掉调用处的强转。
     *
     * @param player 玩家，为 null 时静默忽略
     * @param key    键名
     */
    public static void send(org.bukkit.entity.Player player, String key) {
        send((org.bukkit.command.CommandSender) player, key);
    }

    /**
     * 取一条带前缀的消息、做变量替换后发送。
     *
     * @param sender 接收者，为 null 时静默忽略
     * @param key    键名
     * @param pairs  键值对，按 {@code 占位符, 值, ...} 顺序传入
     */
    public static void send(org.bukkit.command.CommandSender sender, String key, String... pairs) {
        if (sender == null) {
            return;
        }
        sender.sendMessage(format(key, pairs));
    }

    /**
     * 当前使用的消息前缀。
     *
     * @return 前缀原文
     */
    public static String getPrefix() {
        return prefix;
    }
}
