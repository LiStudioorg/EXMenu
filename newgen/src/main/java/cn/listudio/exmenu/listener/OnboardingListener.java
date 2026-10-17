package cn.listudio.exmenu.listener;

import cn.listudio.exmenu.config.MenuConfig;
import cn.listudio.exmenu.inventory.ConfigProvider;
import cn.listudio.exmenu.util.TextUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.io.File;

/**
 * 首次启动引导。
 *
 * <p>面向新手服主：装好插件后的第一个管理员进服时刻，是整台服务器
 * 唯一"有人愿意读三行提示"的窗口。错过它，用户就要自己在几十条子命令里猜。
 *
 * <p>触发条件（全部满足才发一次）：
 * <ol>
 *   <li>{@code config.yml} 的 {@code firstRunHelp: true}（想关掉随时关）</li>
 *   <li>数据目录下没有 {@code .onboarded} 标记文件（发过一次即永久安静）</li>
 *   <li>进服者持有 {@code exmenu.create} 权限 —— 这是管理员指令集，
 *       普通玩家收到引导反而是噪音</li>
 * </ol>
 *
 * <p>标记文件先写后发：并发进服/异常重连时宁可少发一次，也不重复骚扰。
 */
public final class OnboardingListener implements Listener {

    /** 标记文件名。 */
    private static final String MARKER = ".onboarded";

    private final Plugin plugin;
    private final ConfigProvider configProvider;

    /**
     * 构造。
     *
     * @param plugin         插件实例
     * @param configProvider 配置提供者
     */
    public OnboardingListener(Plugin plugin, ConfigProvider configProvider) {
        this.plugin = plugin;
        this.configProvider = configProvider;
    }

    /**
     * 进服事件。
     *
     * @param event 事件
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        MenuConfig config = configProvider.get();
        if (config == null || !config.isFirstRunHelp()) {
            return;
        }
        if (!player.hasPermission("exmenu.create")) {
            return;
        }
        File marker = new File(plugin.getDataFolder(), MARKER);
        if (marker.exists()) {
            return;
        }
        // 先落标记再发消息：避免两个管理员同时进服被各发一遍
        try {
            File parent = marker.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return;
            }
            if (!marker.createNewFile()) {
                return;
            }
        } catch (Exception e) {
            return;
        }
        sendGuide(player);
    }

    /**
     * 发送引导消息。
     *
     * @param player 管理员玩家
     */
    private void sendGuide(Player player) {
        player.sendMessage(TextUtil.color("&8&m--------&r &bEXMenu &8&m--------"));
        player.sendMessage(TextUtil.color("&e你好，看起来这是本服第一次使用 EXMenu！"));
        player.sendMessage(TextUtil.color("&7· &f/exmenu open example &7- 先看看示例菜单"));
        player.sendMessage(TextUtil.color("&7· &f/exmenu edit <菜单名> &7- 全程可视化编辑，不用写 YAML"));
        player.sendMessage(TextUtil.color("&7· &f/exmenu settings &7- 常用配置改成按钮，点一下生效"));
        player.sendMessage(TextUtil.color("&7· &f/exmenu library &7- 物品库存放复杂图标"));
        player.sendMessage(TextUtil.color("&7· &f/exmenu check &7- 配置出错时先跑这个"));
        player.sendMessage(TextUtil.color("&8这条提示只会出现一次；"
                + "关闭它请设置 config.yml 的 firstRunHelp: false"));
    }
}
