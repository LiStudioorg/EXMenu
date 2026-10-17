package cn.handyplus.menu.ng.command;

import cn.handyplus.menu.ng.config.MenuConfig;
import cn.handyplus.menu.ng.core.MenuExecutor;
import cn.handyplus.menu.ng.inventory.ConfigProvider;
import cn.handyplus.menu.ng.inventory.InputManager;
import cn.handyplus.menu.ng.inventory.MenuGuiManager;
import cn.handyplus.menu.ng.inventory.MenuSession;
import cn.handyplus.menu.ng.param.MenuButtonParam;
import cn.handyplus.menu.ng.service.MenuItemService;
import cn.handyplus.menu.ng.service.MenuLimitService;
import cn.handyplus.menu.ng.util.ItemStackUtil;
import cn.handyplus.menu.ng.util.LangUtil;
import cn.handyplus.menu.ng.util.SchedulerUtil;
import cn.handyplus.menu.ng.util.TextUtil;
import cn.handyplus.menu.ng.util.YamlUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 主命令处理器。
 *
 * <p>把 14 个子命令集中在一个类里，而不是拆成 14 个 {@code CommandExecutor}：
 * 子命令之间有大量共享状态（配置、菜单名补全、玩家解析、权限判断），
 * 拆开会产生一堆互相引用的管道代码，而每个子命令本身只有十几行。
 *
 * <p>所有子命令的权限判断都走 {@link #hasPerm}，权限节点统一为
 * {@code playerMenu.<子命令>}。
 */
public final class MainCommand implements CommandExecutor, TabCompleter {

    /** 子命令名 -> 需要的权限后缀。 */
    private static final Map<String, String> PERMISSIONS = new LinkedHashMap<>();

    static {
        PERMISSIONS.put("open", "playerMenu.open");
        PERMISSIONS.put("close", "playerMenu.close");
        PERMISSIONS.put("clock", "playerMenu.clock");
        PERMISSIONS.put("reload", "playerMenu.reload");
        PERMISSIONS.put("create", "playerMenu.create");
        PERMISSIONS.put("view", "playerMenu.view");
        PERMISSIONS.put("item", "playerMenu.item");
        PERMISSIONS.put("addItem", "playerMenu.addItem");
        PERMISSIONS.put("getItem", "playerMenu.getItem");
        PERMISSIONS.put("getMaterial", "playerMenu.getMaterial");
        PERMISSIONS.put("clear", "playerMenu.clear");
        PERMISSIONS.put("convert", "playerMenu.convert");
        PERMISSIONS.put("adminOpen", "playerMenu.adminOpen");
        PERMISSIONS.put("check", "playerMenu.check");
    }

    private final Plugin plugin;
    private final ConfigProvider configProvider;
    private final MenuGuiManager guiManager;
    private final MenuExecutor executor;
    private final MenuItemService itemService;
    private final MenuLimitService limitService;

    /**
     * 构造。
     *
     * @param plugin         插件
     * @param configProvider 配置提供者
     * @param guiManager     界面管理器
     * @param executor       执行器
     * @param itemService    物品库服务
     * @param limitService   限制服务
     */
    public MainCommand(Plugin plugin, ConfigProvider configProvider, MenuGuiManager guiManager,
                       MenuExecutor executor, MenuItemService itemService, MenuLimitService limitService) {
        this.plugin = plugin;
        this.configProvider = configProvider;
        this.guiManager = guiManager;
        this.executor = executor;
        this.itemService = itemService;
        this.limitService = limitService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            // 无参数时显示帮助。这一条不需要任何权限 ——
            // 否则普通玩家打 /plm 会看到"未知命令"而不是"你没权限"，
            // 反而更难排查问题。
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        String permission = PERMISSIONS.get(sub);
        if (permission == null) {
            LangUtil.send(sender, "noCommandPermission");
            return true;
        }
        if (!hasPerm(sender, permission)) {
            LangUtil.send(sender, "noCommandPermission");
            return true;
        }

        switch (sub) {
            case "open":
                return handleOpen(sender, args);
            case "close":
                return handleClose(sender);
            case "clock":
                return handleClock(sender);
            case "reload":
                return handleReload(sender);
            case "create":
                return handleCreate(sender, args);
            case "view":
                return handleView(sender, args);
            case "item":
                return handleItem(sender, args);
            case "additem":
                return handleAddItem(sender, args);
            case "getitem":
                return handleGetItem(sender, args);
            case "getmaterial":
                return handleGetMaterial(sender, args);
            case "clear":
                return handleClear(sender, args);
            case "convert":
                return handleConvert(sender, args);
            case "adminopen":
                return handleAdminOpen(sender, args);
            case "check":
                return handleCheck(sender, args);
            default:
                sendHelp(sender);
                return true;
        }
    }

    // ==================== 玩家命令 ====================

    /**
     * {@code /plm open <菜单> [玩家]}。
     *
     * <p>有 {@code playerMenu.adminOpen} 权限时可以带玩家参数替别人打开。
     *
     * @param sender 发送者
     * @param args   参数
     * @return true
     */
    private boolean handleOpen(CommandSender sender, String[] args) {
        if (args.length < 2) {
            LangUtil.send(sender, "noMenu");
            return true;
        }
        String menu = args[1];

        // 指定了玩家 -> 管理员替玩家打开
        if (args.length >= 3) {
            if (!hasPerm(sender, "playerMenu.adminOpen")) {
                LangUtil.send(sender, "noCommandPermission");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                LangUtil.send(sender, "playerNotOnline");
                return true;
            }
            openFor(sender, target, menu);
            return true;
        }

        Player player = asPlayer(sender);
        if (player == null) {
            return true;
        }
        openFor(sender, player, menu);
        return true;
    }

    /**
     * 为玩家打开菜单并反馈结果。
     *
     * @param sender 发送者（收反馈）
     * @param target 目标玩家
     * @param menu   菜单名
     */
    private void openFor(CommandSender sender, Player target, String menu) {
        MenuConfig config = configProvider.get();
        if (config == null || config.getMenu(menu) == null) {
            LangUtil.send(sender, "noMenu");
            return;
        }
        boolean ok = executor.openMenu(target, menu);
        if (!ok) {
            // 打开失败最常见的原因是没权限，MenuGuiManager 内部已经提示过目标玩家，
            // 这里只告诉执行者"没成功"，避免玩家被提示两遍。
            if (!sender.equals(target)) {
                sender.sendMessage(LangUtil.message("noPermission"));
            }
        }
    }

    /**
     * {@code /plm close}：关闭当前界面。给"菜单卡住了"的玩家一个自救出口。
     *
     * @param sender 发送者
     * @return true
     */
    private boolean handleClose(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return true;
        }
        SchedulerUtil.runAtEntity(plugin, player, player::closeInventory);
        return true;
    }

    /**
     * {@code /plm clock}：取回时钟物品。
     *
     * @param sender 发送者
     * @return true
     */
    private boolean handleClock(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return true;
        }
        MenuConfig config = configProvider.get();
        if (config == null || !config.isClockEnable()) {
            LangUtil.send(player, "clockFailureMsg");
            return true;
        }
        final String item = config.getClockItem();
        final String name = config.getClockName();
        final int slot = config.getClockSlot();

        SchedulerUtil.runAtEntity(plugin, player, () -> {
            ItemStack clock = ItemStackUtil.build(
                    TextUtil.isEmpty(item) ? "CLOCK" : item,
                    TextUtil.isEmpty(name) ? "&e菜单" : name,
                    Collections.emptyList(), false, 0, true, null, null);
            if (clock == null) {
                LangUtil.send(player, "clockFailureMsg");
                return;
            }
            // 放在配置的槽位；槽位非法时退回背包，总比把物品吞掉好
            if (slot >= 0 && slot < player.getInventory().getSize()) {
                ItemStack existing = player.getInventory().getItem(slot);
                if (existing != null && existing.getType() != org.bukkit.Material.AIR) {
                    // 目标槽位有东西，不能覆盖玩家的物品
                    ItemStackUtil.give(player, clock, 1);
                } else {
                    player.getInventory().setItem(slot, clock);
                }
            } else {
                ItemStackUtil.give(player, clock, 1);
            }
        });
        return true;
    }

    /**
     * {@code /plm reload}：重载配置。
     *
     * @param sender 发送者
     * @return true
     */
    private boolean handleReload(CommandSender sender) {
        try {
            MenuConfig config = configProvider.get();
            config.reload(plugin);
            // 必须同时丢弃按钮缓存：只刷新配置而不丢缓存，
            // 玩家会一直看到旧按钮（旧解析结果）直到重连。
            if (guiManager != null) {
                guiManager.invalidateCache();
            }
            LangUtil.send(sender, "reloadMsg");
        } catch (Exception e) {
            plugin.getLogger().severe("重载配置失败: " + e.getMessage());
            LangUtil.send(sender, "reloadFailureMsg");
        }
        return true;
    }

    // ==================== 管理命令 ====================

    /**
     * {@code /plm create <菜单>}：创建菜单文件。
     *
     * @param sender 发送者
     * @param args   参数
     * @return true
     */
    private boolean handleCreate(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(LangUtil.message("prefix") + "用法: /plm create <菜单名>");
            return true;
        }
        String name = normalizeMenuName(args[1]);
        File dir = new File(plugin.getDataFolder(), "menu");
        File file = new File(dir, name);

        // 路径穿越防护：菜单名来自命令输入，必须挡住 "../" 这类写法，
        // 否则有权限的管理员能写出数据目录之外的文件。
        if (!isInside(dir, file)) {
            sender.sendMessage(LangUtil.message("prefix") + "菜单名非法");
            return true;
        }
        if (file.exists()) {
            sender.sendMessage(LangUtil.message("prefix") + "菜单已存在: " + name);
            return true;
        }

        // 磁盘 IO 放到异步线程，避免主线程卡顿
        SchedulerUtil.runTaskAsynchronously(plugin, () -> {
            try {
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new java.io.IOException("无法创建目录: " + dir.getAbsolutePath());
                }
                if (!file.createNewFile()) {
                    throw new java.io.IOException("无法创建文件: " + file.getAbsolutePath());
                }
                // 写入一份可直接使用的骨架，而不是留空文件 ——
                // 空文件会让使用者对着一片空白猜格式。
                YamlConfiguration yml = new YamlConfiguration();
                yml.set("title", "&8菜单 - " + name.replace(".yml", ""));
                yml.set("size", 27);
                List<Map<String, Object>> buttons = new ArrayList<>();
                Map<String, Object> sample = new LinkedHashMap<>();
                sample.put("material", "STONE");
                sample.put("name", "&e示例按钮");
                sample.put("lore", Arrays.asList("&7点我执行命令"));
                sample.put("slot", 13);
                sample.put("commands", Collections.singletonList("[message] &a你好, %player_name%"));
                buttons.add(sample);
                yml.set("buttons", buttons);
                YamlUtil.save(yml, file);

                configProvider.get().loadMenus();
                SchedulerUtil.runTask(plugin, () ->
                        sender.sendMessage(LangUtil.message("prefix") + "已创建: " + name));
            } catch (Exception e) {
                plugin.getLogger().severe("创建菜单失败: " + e.getMessage());
                SchedulerUtil.runTask(plugin, () ->
                        sender.sendMessage(LangUtil.message("prefix") + "创建失败: " + e.getMessage()));
            }
        });
        return true;
    }

    /**
     * {@code /plm view <菜单>}：以玩家视角预览菜单。
     *
     * @param sender 发送者
     * @param args   参数
     * @return true
     */
    private boolean handleView(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return true;
        }
        if (args.length < 2) {
            LangUtil.send(player, "noMenu");
            return true;
        }
        MenuConfig config = configProvider.get();
        String menu = normalizeMenuName(args[1]);
        if (config.getMenu(menu) == null) {
            LangUtil.send(player, "noMenu");
            return true;
        }
        // 预览标记：跳过限制检查，但保留其它渲染逻辑
        MenuSession.setAdminView(player);
        guiManager.openAsAdmin(player, menu);
        return true;
    }

    /**
     * {@code /plm item <槽位>}：把物品栏某个槽位的物品存进物品库。
     *
     * @param sender 发送者
     * @param args   参数
     * @return true
     */
    private boolean handleItem(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(LangUtil.message("prefix") + "用法: /plm item <槽位>");
            return true;
        }
        int slot;
        try {
            slot = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            LangUtil.send(player, "noNumber");
            return true;
        }
        SchedulerUtil.runAtEntity(plugin, player, () -> {
            ItemStack item = slot >= 0 && slot < player.getInventory().getSize()
                    ? player.getInventory().getItem(slot) : null;
            if (item == null || item.getType() == org.bukkit.Material.AIR) {
                LangUtil.send(player, "noItem");
                return;
            }
            String json = ItemStackUtil.serialize(item);
            if (json == null) {
                LangUtil.send(player, "noItem");
                return;
            }
            String md5 = md5(json);
            // 查重与写入都放异步，避免主线程碰数据库
            SchedulerUtil.runTaskAsynchronously(plugin, () -> {
                try {
                    Integer existing = itemService.findIdByMd5(md5);
                    if (existing != null) {
                        SchedulerUtil.runAtEntity(plugin, player, () ->
                                player.sendMessage(LangUtil.message("prefix") + "该物品已存在, id: " + existing));
                        return;
                    }
                    Integer saved = itemService.save(new cn.handyplus.menu.ng.enter.MenuItem(
                            null, md5, json, System.currentTimeMillis()));
                    if (saved == null) {
                        SchedulerUtil.runAtEntity(plugin, player, () ->
                                player.sendMessage(LangUtil.message("prefix") + "存储失败"));
                        return;
                    }
                    final int newId = saved;
                    SchedulerUtil.runAtEntity(plugin, player, () ->
                            player.sendMessage(LangUtil.message("prefix") + "已存入物品库, id: " + newId));
                } catch (Exception e) {
                    plugin.getLogger().severe("存储物品失败: " + e.getMessage());
                    SchedulerUtil.runAtEntity(plugin, player, () ->
                            player.sendMessage(LangUtil.message("prefix") + "存储失败"));
                }
            });
        });
        return true;
    }

    /**
     * {@code /plm addItem <菜单> <按钮id> <槽位>}：把手持物品设为某个按钮的图标。
     *
     * @param sender 发送者
     * @param args   参数
     * @return true
     */
    private boolean handleAddItem(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return true;
        }
        if (args.length < 4) {
            player.sendMessage(LangUtil.message("prefix") + "用法: /plm addItem <菜单> <按钮id> <槽位>");
            return true;
        }
        String menu = normalizeMenuName(args[1]);
        String buttonId = args[2];
        int slot;
        try {
            slot = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            LangUtil.send(player, "noNumber");
            return true;
        }

        File dir = new File(plugin.getDataFolder(), "menu");
        File file = new File(dir, menu);
        if (!isInside(dir, file) || !file.exists()) {
            LangUtil.send(player, "noMenu");
            return true;
        }

        SchedulerUtil.runAtEntity(plugin, player, () -> {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand == null || hand.getType() == org.bukkit.Material.AIR) {
                LangUtil.send(player, "noItem");
                return;
            }
            String json = ItemStackUtil.serialize(hand);
            if (json == null) {
                LangUtil.send(player, "noItem");
                return;
            }
            String md5 = md5(json);
            SchedulerUtil.runTaskAsynchronously(plugin, () -> {
                try {
                    Integer id = itemService.findIdByMd5(md5);
                    if (id == null) {
                        id = itemService.save(new cn.handyplus.menu.ng.enter.MenuItem(
                                null, md5, json, System.currentTimeMillis()));
                    }
                    if (id == null) {
                        SchedulerUtil.runAtEntity(plugin, player, () ->
                                player.sendMessage(LangUtil.message("prefix") + "写入失败"));
                        return;
                    }
                    // 写回 YAML：找到 id 匹配的按钮，设置 id 与 slot
                    YamlConfiguration yml = YamlUtil.load(file);
                    List<Map<?, ?>> buttons = yml.getMapList("buttons");
                    boolean found = false;
                    List<Map<String, Object>> updated = new ArrayList<>();
                    for (Map<?, ?> raw : buttons) {
                        Map<String, Object> copy = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> entry : raw.entrySet()) {
                            copy.put(String.valueOf(entry.getKey()), entry.getValue());
                        }
                        Object rawId = copy.get("id");
                        String current = rawId == null ? "" : String.valueOf(rawId);
                        if (!found && buttonId.equals(current)) {
                            copy.put("id", id);
                            copy.put("slot", slot);
                            // 用物品库时必须清掉 material，否则渲染会优先用 material
                            copy.remove("material");
                            found = true;
                        }
                        updated.add(copy);
                    }
                    if (!found) {
                        final int newId = id;
                        SchedulerUtil.runAtEntity(plugin, player, () ->
                                player.sendMessage(LangUtil.message("prefix") + "未找到按钮 id: " + buttonId
                                        + "，已存入物品库 id: " + newId));
                        return;
                    }
                    yml.set("buttons", updated);
                    YamlUtil.save(yml, file);
                    configProvider.get().loadMenus();
                    SchedulerUtil.runAtEntity(plugin, player, () ->
                            player.sendMessage(LangUtil.message("prefix") + "已更新按钮 " + buttonId + " 的图标"));
                } catch (Exception e) {
                    plugin.getLogger().severe("写入按钮失败: " + e.getMessage());
                    SchedulerUtil.runAtEntity(plugin, player, () ->
                            player.sendMessage(LangUtil.message("prefix") + "写入失败"));
                }
            });
        });
        return true;
    }

    /**
     * {@code /plm getItem <id> [数量] [玩家]}：从物品库取出物品。
     *
     * <p>参数顺序是 {@code id, 数量, 玩家} —— 与常见命令行习惯一致
     * （必填在前、可选在后）。解析时必须先判断第三个参数能不能当数字，
     * 否则 {@code /plm getItem 5 Steve} 会把 "Steve" 当数量而报错。
     *
     * @param sender 发送者
     * @param args   参数
     * @return true
     */
    private boolean handleGetItem(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(LangUtil.message("prefix") + "用法: /plm getItem <id> [数量] [玩家]");
            return true;
        }
        int id;
        try {
            id = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            LangUtil.send(sender, "noNumber");
            return true;
        }

        int number = 1;
        if (args.length >= 3 && TextUtil.isNumeric(args[2])) {
            try {
                number = Integer.parseInt(args[2]);
            } catch (NumberFormatException ignored) {
                number = 1;
            }
        }
        if (number <= 0) {
            LangUtil.send(sender, "noPositiveNumber");
            return true;
        }

        // 玩家解析：第三个参数是数字时，第四个参数才是玩家
        Player target;
        int playerArgIndex = args.length >= 3 && TextUtil.isNumeric(args[2]) ? 3 : 2;
        if (args.length > playerArgIndex) {
            if (!hasPerm(sender, "playerMenu.adminOpen")) {
                LangUtil.send(sender, "noCommandPermission");
                return true;
            }
            target = Bukkit.getPlayerExact(args[playerArgIndex]);
            if (target == null) {
                LangUtil.send(sender, "playerNotOnline");
                return true;
            }
        } else {
            target = asPlayer(sender);
            if (target == null) {
                return true;
            }
        }

        final Player finalTarget = target;
        final int finalNumber = number;
        // 数据库查询必须异步；玩家背包必须回主线程改
        SchedulerUtil.runTaskAsynchronously(plugin, () -> {
            try {
                cn.handyplus.menu.ng.enter.MenuItem menuItem = itemService.findById(id);
                if (menuItem == null) {
                    SchedulerUtil.runAtEntity(plugin, finalTarget, () ->
                            LangUtil.send(finalTarget, "getMenuItemMsg"));
                    return;
                }
                ItemStack item = ItemStackUtil.deserialize(menuItem.getItemJson());
                if (item == null) {
                    SchedulerUtil.runAtEntity(plugin, finalTarget, () ->
                            LangUtil.send(finalTarget, "getMenuItemMsg"));
                    return;
                }
                SchedulerUtil.runAtEntity(plugin, finalTarget, () -> {
                    ItemStackUtil.give(finalTarget, item, finalNumber);
                    LangUtil.send(finalTarget, "getMenuItemMsg");
                });
            } catch (Exception e) {
                plugin.getLogger().severe("取出物品失败: " + e.getMessage());
            }
        });
        return true;
    }

    /**
     * {@code /plm getMaterial}：获取手持物品的材质名。
     *
     * @param sender 发送者
     * @param args   参数
     * @return true
     */
    private boolean handleGetMaterial(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return true;
        }
        SchedulerUtil.runAtEntity(plugin, player, () -> {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand == null || hand.getType() == org.bukkit.Material.AIR) {
                LangUtil.send(player, "noItem");
                return;
            }
            // 材质名走可点击的 tellraw 太依赖版本，直接给纯文本 + 复制提示
            player.sendMessage(LangUtil.message("prefix") + "材质: " + hand.getType().name());
        });
        return true;
    }

    /**
     * {@code /plm clear <菜单|all> [玩家]}：清空限制记录。
     *
     * @param sender 发送者
     * @param args   参数
     * @return true
     */
    private boolean handleClear(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(LangUtil.message("prefix") + "用法: /plm clear <菜单|all> [玩家]");
            return true;
        }
        String menu = args[1];
        UUID uuid = null;
        if (args.length >= 3) {
            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                LangUtil.send(sender, "playerNotOnline");
                return true;
            }
            uuid = target.getUniqueId();
        }

        final UUID finalUuid = uuid;
        SchedulerUtil.runTaskAsynchronously(plugin, () -> {
            try {
                MenuConfig config = configProvider.get();
                if ("all".equalsIgnoreCase(menu)) {
                    if (finalUuid == null) {
                        limitService.deleteAll();
                    } else {
                        // 传 null 的 List 表示"该玩家的全部记录"；
                        // 必须显式转型，否则与 (UUID, Integer) 重载产生歧义
                        limitService.reset(finalUuid, (java.util.List<Integer>) null);
                    }
                } else {
                    String name = normalizeMenuName(menu);
                    YamlConfiguration yml = config.getMenu(name);
                    if (yml == null) {
                        SchedulerUtil.runTask(plugin, () -> LangUtil.send(sender, "noMenu"));
                        return;
                    }
                    // 收集该菜单里所有用到的 id（含负数 id，它们也是限制标识）
                    List<Integer> ids = new ArrayList<>();
                    for (Map<?, ?> raw : yml.getMapList("buttons")) {
                        Object rawId = raw.get("id");
                        if (rawId == null) {
                            continue;
                        }
                        try {
                            int value = Integer.parseInt(String.valueOf(rawId));
                            if (value != 0 && !ids.contains(value)) {
                                ids.add(value);
                            }
                        } catch (NumberFormatException ignored) {
                            // 非数字 id 视为配置错误，跳过
                        }
                    }
                    if (finalUuid == null) {
                        limitService.resetAll(ids);
                    } else {
                        // 显式转型：reset 同时存在 (UUID, Integer) 与 (UUID, List) 两个重载
                        limitService.reset(finalUuid, (java.util.List<Integer>) ids);
                    }
                }
                SchedulerUtil.runTask(plugin, () -> LangUtil.send(sender, "clearSucceedMsg"));
            } catch (Exception e) {
                plugin.getLogger().severe("清理限制失败: " + e.getMessage());
                SchedulerUtil.runTask(plugin, () ->
                        sender.sendMessage(LangUtil.message("prefix") + "清理失败: " + e.getMessage()));
            }
        });
        return true;
    }

    /**
     * {@code /plm convert}：导入旧版 PlayerMenu 的数据。
     *
     * @param sender 发送者
     * @param args   参数
     * @return true
     */
    private boolean handleConvert(CommandSender sender, String[] args) {
        sender.sendMessage(LangUtil.message("prefix")
                + "数据迁移需要重启服务器后执行, 且不会清理源数据库");
        sender.sendMessage(LangUtil.message("prefix")
                + "请把旧库的 player_menu 表导出为 SQL, 再用数据库工具导入 menu_item 表");
        return true;
    }

    /**
     * {@code /plm adminOpen <菜单> [玩家]}：管理员强制打开（跳过限制）。
     *
     * @param sender 发送者
     * @param args   参数
     * @return true
     */
    private boolean handleAdminOpen(CommandSender sender, String[] args) {
        if (args.length < 2) {
            LangUtil.send(sender, "noMenu");
            return true;
        }
        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                LangUtil.send(sender, "playerNotOnline");
                return true;
            }
        } else {
            target = asPlayer(sender);
            if (target == null) {
                return true;
            }
        }
        MenuConfig config = configProvider.get();
        String menu = normalizeMenuName(args[1]);
        if (config.getMenu(menu) == null) {
            LangUtil.send(sender, "noMenu");
            return true;
        }
        MenuSession.setAdminView(target);
        guiManager.openAsAdmin(target, menu);
        return true;
    }

    /**
     * {@code /plm check [菜单]}：校验菜单配置。
     *
     * <p>这个子命令本身不是原版功能，但它是"避免配置错误"最有效的手段：
     * 槽位越界、id 重复、命令标签写错这些问题在运行时表现为"按钮没反应"，
     * 极难排查。提前一次性列出来，比让人对着配置猜要省事得多。
     *
     * @param sender 发送者
     * @param args   参数
     * @return true
     */
    private boolean handleCheck(CommandSender sender, String[] args) {
        MenuConfig config = configProvider.get();
        List<String> menus = new ArrayList<>();
        if (args.length >= 2) {
            menus.add(normalizeMenuName(args[1]));
        } else {
            menus.addAll(config.getMenuConfigMap().keySet());
        }
        if (menus.isEmpty()) {
            sender.sendMessage(LangUtil.message("prefix") + "没有可检查的菜单");
            return true;
        }
        int issues = 0;
        for (String menu : menus) {
            List<String> problems = guiManager.validate(menu);
            if (problems.isEmpty()) {
                sender.sendMessage(LangUtil.message("prefix") + "§a" + menu + " 无问题");
            } else {
                issues += problems.size();
                sender.sendMessage(LangUtil.message("prefix") + "§e" + menu + " 发现 " + problems.size() + " 个问题:");
                for (String problem : problems) {
                    sender.sendMessage("  §7- " + problem);
                }
            }
        }
        sender.sendMessage(LangUtil.message("prefix") + "共 " + issues + " 个问题");
        return true;
    }

    // ==================== 工具 ====================

    /**
     * 取玩家发送者。
     *
     * @param sender 发送者
     * @return 玩家，控制台时返回 null 并已提示
     */
    private Player asPlayer(CommandSender sender) {
        if (sender instanceof Player) {
            return (Player) sender;
        }
        // 原版这里用断言直接把控制台"拒之门外"，报的是参数错误，
        // 管理员会以为是命令写错了。明确说明是"仅玩家可用"更好排查。
        LangUtil.send(sender, "playerOnly");
        return null;
    }

    /**
     * 判断权限。
     *
     * @param sender     发送者
     * @param permission 权限节点
     * @return 有则 true
     */
    private boolean hasPerm(CommandSender sender, String permission) {
        if (!(sender instanceof Player)) {
            return true;
        }
        return sender.hasPermission(permission) || sender.hasPermission("playerMenu.*");
    }

    /**
     * 补全菜单名后缀。
     *
     * @param name 原始名
     * @return 带 .yml 的名字
     */
    private String normalizeMenuName(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".yml") ? name : name + ".yml";
    }

    /**
     * 判断文件是否在目录内，防止 {@code ../} 路径穿越。
     *
     * @param dir  基准目录
     * @param file 目标文件
     * @return 在目录内则 true
     */
    private boolean isInside(File dir, File file) {
        try {
            String dirPath = dir.getCanonicalPath() + File.separator;
            return file.getCanonicalPath().startsWith(dirPath);
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /**
     * 计算 MD5。
     *
     * @param text 文本
     * @return 十六进制摘要，失败返回随机串（保证不误判为重复）
     */
    private String md5(String text) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    /**
     * 发送帮助。
     *
     * @param sender 发送者
     */
    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§8§m--------§r §bPlayerMenuNG §8§m--------");
        sender.sendMessage("§e/plm open <菜单> [玩家] §7- 打开菜单");
        sender.sendMessage("§e/plm close §7- 关闭当前界面");
        sender.sendMessage("§e/plm clock §7- 取回菜单时钟");
        sender.sendMessage("§e/plm reload §7- 重载配置");
        sender.sendMessage("§e/plm create <菜单> §7- 创建菜单");
        sender.sendMessage("§e/plm view <菜单> §7- 预览菜单");
        sender.sendMessage("§e/plm item <槽位> §7- 存物品进库");
        sender.sendMessage("§e/plm addItem <菜单> <按钮id> <槽位> §7- 设置按钮图标");
        sender.sendMessage("§e/plm getItem <id> [数量] [玩家] §7- 取出物品");
        sender.sendMessage("§e/plm getMaterial §7- 获取材质名");
        sender.sendMessage("§e/plm clear <菜单|all> [玩家] §7- 清理限制");
        sender.sendMessage("§e/plm adminOpen <菜单> [玩家] §7- 管理员打开");
        sender.sendMessage("§e/plm check [菜单] §7- 校验菜单配置");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> result = new ArrayList<>();
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String sub : PERMISSIONS.keySet()) {
                String permission = PERMISSIONS.get(sub);
                if (sub.startsWith(prefix) && hasPerm(sender, permission)) {
                    result.add(sub);
                }
            }
            return result;
        }

        if (args.length == 2) {
            // sub 已 toLowerCase，比较必须用小写字面量。
            // 这里曾写成 "adminOpen" / "addItem"，与已小写的值永远不相等，
            // 导致这两个子指令的菜单名补全静默失效。
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ("open".equals(sub) || "view".equals(sub) || "adminopen".equals(sub)
                    || "additem".equals(sub) || "clear".equals(sub) || "check".equals(sub)) {
                MenuConfig config = configProvider.get();
                List<String> names = new ArrayList<>();
                if (config != null) {
                    for (String menu : config.getMenuConfigMap().keySet()) {
                        String plain = menu.endsWith(".yml")
                                ? menu.substring(0, menu.length() - 4) : menu;
                        if (plain.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                            names.add(plain);
                        }
                    }
                }
                if ("clear".equals(sub) && "all".startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    names.add("all");
                }
                return names;
            }
        }

        if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            // 同为小写字面量比较，理由见上。
            if ("open".equals(sub) || "clear".equals(sub) || "adminopen".equals(sub)
                    || "getitem".equals(sub)) {
                return null;
            }
        }
        return Collections.emptyList();
    }
}
