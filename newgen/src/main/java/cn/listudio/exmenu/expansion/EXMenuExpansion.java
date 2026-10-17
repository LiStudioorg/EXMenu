package cn.listudio.exmenu.expansion;

import cn.listudio.exmenu.core.LimitCache;
import cn.listudio.exmenu.core.MenuChecker;
import cn.listudio.exmenu.enter.MenuLimit;
import cn.listudio.exmenu.inventory.MenuGuiManager;
import cn.listudio.exmenu.param.MenuButtonParam;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * EXMenu 官方 PAPI 变量（PlaceholderAPI 在位时才注册）。
 *
 * <p>解决的问题：限购与冷却的<b>状态回显</b>。此前只能消费 PAPI，
 * 服主想在按钮 Lore 上显示"今日剩余 2 次"就无从下手。现在可以写：
 * <pre>
 *   dynamicLore:
 *     - '&7剩余次数: %exmenu_limit_remaining_-1%'
 *     - '&7冷却剩余: %exmenu_cd_seconds_-1% 秒'
 * </pre>
 *
 * <p>参数形态（{@code <id>} 为按钮的全局唯一 id）：
 * <table>
 *   <caption>变量表</caption>
 *   <tr><td>{@code limit_remaining_<id>}</td><td>剩余可用次数（未配 limit 显示 -）</td></tr>
 *   <tr><td>{@code limit_used_<id>}</td><td>已用次数</td></tr>
 *   <tr><td>{@code cd_seconds_<id>}</td><td>冷却剩余秒数（不在冷却中为 0）</td></tr>
 * </table>
 *
 * <p>读取走 {@link LimitCache}（异步刷新的快照缓存），
 * <b>绝不在 onRequest 里做数据库 IO</b> —— 该方法通常运行在渲染 Lore 的主线程。
 */
public final class EXMenuExpansion extends PlaceholderExpansion {

    /** 插件版本字符串。 */
    private final String version;

    /** 限制缓存。 */
    private final LimitCache limitCache;

    /** 界面管理器（按 id 查找按钮配置）。 */
    private final MenuGuiManager guiManager;

    /**
     * 构造。
     *
     * @param version    插件版本（展示用）
     * @param limitCache 限制缓存
     * @param guiManager 界面管理器
     */
    public EXMenuExpansion(String version, LimitCache limitCache, MenuGuiManager guiManager) {
        this.version = version;
        this.limitCache = limitCache;
        this.guiManager = guiManager;
    }

    @Override
    public String getIdentifier() {
        return "exmenu";
    }

    @Override
    public String getAuthor() {
        return "LiStudioorg";
    }

    @Override
    public String getVersion() {
        return version;
    }

    /** 插件禁用时保留注册（随 PAPI 生命周期）。 */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, String params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        String lower = params.toLowerCase(java.util.Locale.ROOT);
        String kind;
        String idText;
        int split = lower.indexOf('_');
        if (split < 0) {
            return null;
        }
        // 形如 limit_remaining_-1 / cd_seconds_5：按"前缀 + 尾部 id"解析
        if (lower.startsWith("limit_remaining_")) {
            kind = "limit_remaining";
            idText = params.substring("limit_remaining_".length());
        } else if (lower.startsWith("limit_used_")) {
            kind = "limit_used";
            idText = params.substring("limit_used_".length());
        } else if (lower.startsWith("cd_seconds_")) {
            kind = "cd_seconds";
            idText = params.substring("cd_seconds_".length());
        } else {
            return null;
        }
        int id;
        try {
            id = Integer.parseInt(idText.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (id == 0 || !(offlinePlayer instanceof Player)) {
            return null;
        }
        Player player = (Player) offlinePlayer;
        MenuButtonParam param = guiManager.findButtonByGlobalId(id);
        if (param == null) {
            return "-";
        }
        MenuLimit limit = limitCache.get(player, id);
        switch (kind) {
            case "limit_remaining":
                if (param.getLimit() <= 0) {
                    return "-";
                }
                return String.valueOf(MenuChecker.remaining(param, limit));
            case "limit_used":
                int used = limit == null || limit.getNumber() == null ? 0 : limit.getNumber();
                return String.valueOf(used);
            case "cd_seconds":
                long remaining = MenuChecker.remainingCd(param, limit);
                return String.valueOf((remaining + 999) / 1000);
            default:
                return null;
        }
    }
}
