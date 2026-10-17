package cn.handyplus.menu.ng.core;

import cn.handyplus.menu.ng.constants.CommandTypeEnum;
import cn.handyplus.menu.ng.enter.MenuLimit;
import cn.handyplus.menu.ng.param.MenuButtonParam;
import cn.handyplus.menu.ng.service.MenuLimitService;
import cn.handyplus.menu.ng.util.EconomyUtil;
import cn.handyplus.menu.ng.util.ItemStackUtil;
import cn.handyplus.menu.ng.util.LangUtil;
import cn.handyplus.menu.ng.util.PlaceholderUtil;
import cn.handyplus.menu.ng.util.SchedulerUtil;
import cn.handyplus.menu.ng.util.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.List;

/**
 * 按钮动作执行器。
 *
 * <p>这是插件的核心。一次点击从进入本类到结束，经过四个阶段：
 * <ol>
 *   <li><b>前置校验</b> —— 商店交易能否成立（余额、物品够不够）。<b>失败时立即返回，
 *       不记录任何使用次数、不写冷却</b>。这是关键：原版先扣限制再校验，导致玩家
 *       点一次余额不足的按钮也会被扣掉次数。</li>
 *   <li><b>记录使用</b> —— 更新次数与冷却。只有校验通过才走到这里。</li>
 *   <li><b>播放音效</b> —— 成功音效。</li>
 *   <li><b>执行动作链</b> —— 先跑通用 {@code commands}，再按点击类型跑
 *       {@code actions.left/right/middle}。</li>
 * </ol>
 *
 * <p>动作链是<b>可中断的递归</b>：{@code [delay]} 会把剩余动作挂到下一次调度上，
 * 遇到空行或无法识别的标签则整条链终止 —— 静默跳过会让配置错误极难发现。
 */
public final class MenuExecutor {

    /**
     * 玩家输入值的传递。
     *
     * <p>历史上这里用 {@code ThreadLocal} 传递输入值，但那是错的：输入流程在
     * {@code AsyncPlayerChatEvent} 的异步线程里写入，之后通过 {@code runAtEntity}
     * 切到实体线程读取，两个线程不同，读到的永远是 null —— {@code %input%} 恒为空。
     * 而且写入侧的线程局部值会在池化线程上残留，把上一个玩家的输入泄漏给下一个。
     *
     * <p>现在改为把输入值挂在按钮对象本身（{@link MenuButtonParam#setInputValue}），
     * 随着参数一路传下去，与线程无关。
     */
    /** 限制 DAO。 */
    private final MenuLimitService limitService;

    /** 插件实例，用于调度与日志。 */
    private final Plugin plugin;

    /** 配置访问。 */
    private final MenuChecker.ConfigAccessor config;

    /**
     * 构造执行器。
     *
     * @param plugin       插件实例
     * @param limitService 限制 DAO
     * @param config       配置访问
     */
    public MenuExecutor(Plugin plugin, MenuLimitService limitService, MenuChecker.ConfigAccessor config) {
        this.plugin = plugin;
        this.limitService = limitService;
        this.config = config;
    }

    /**
     * 把玩家输入值注入按钮对象，供后续 {@code %input%} 替换使用。
     *
     * @param param 按钮，可为 null（此时什么都不做）
     * @param input 输入内容，null 表示清除
     */
    public static void setInput(MenuButtonParam param, String input) {
        if (param != null) {
            param.setInputValue(input);
        }
    }

    /**
     * 执行一个按钮的完整流程。
     *
     * @param player 玩家
     * @param param  按钮
     * @param limit  该玩家的限制记录，可为 null
     * @param gui    当前界面，用于 {@code [close]} 与刷新，可为 null
     */
    public void execute(Player player, MenuButtonParam param, MenuLimit limit, GuiContext gui) {
        if (player == null || param == null) {
            return;
        }

        // ---- 阶段 1：前置校验。失败则直接返回，不留下任何使用记录 ----
        if (!handleShopBefore(player, param)) {
            playSound(player, param.resolveFailSound());
            return;
        }

        // ---- 阶段 2：记录使用 ----
        applyLimits(player, param);

        // ---- 阶段 3：成功音效 ----
        playSound(player, param.getSound());

        // ---- 阶段 4：动作链 ----
        String inputValue = param.getInputValue();
        runCommands(player, param.getCommands(), gui, 0, inputValue);

        // 按点击类型追加专属动作
        if (param.getEventClickType() != null) {
            switch (param.getEventClickType()) {
                case LEFT:
                    runCommands(player, param.getActionsLeft(), gui, 0, inputValue);
                    break;
                case RIGHT:
                    runCommands(player, param.getActionsRight(), gui, 0, inputValue);
                    break;
                case MIDDLE:
                    runCommands(player, param.getActionsMiddle(), gui, 0, inputValue);
                    break;
                default:
                    break;
            }
        }

        // ---- 收尾：关闭界面 ----
        if (param.isClose() && gui != null) {
            gui.close();
        }
    }

    // ------------------------------------------------------------------
    // 商店
    // ------------------------------------------------------------------

    /**
     * 处理商店交易的前置部分。
     *
     * <p>返回 false 表示交易不成立，调用方应立即中止。<b>本方法不修改任何持久状态</b>：
     * 扣款与给予物品都推迟到校验全部通过之后，避免"扣了钱但东西没给"。
     *
     * @param player 玩家
     * @param param  按钮
     * @return true 表示可以继续（非商店按钮或交易成立）
     */
    private boolean handleShopBefore(Player player, MenuButtonParam param) {
        String shopType = param.getShopType();
        if (TextUtil.isEmpty(shopType)) {
            return true;
        }

        // 只有左右键触发商店；其它点击类型（含 SHIFT 组合键）直接跳过商店，
        // 但仍允许后续动作执行 —— 这是刻意保留的行为，便于把商店按钮
        // 与普通动作按钮合并到同一个槽位。
        boolean isLeft = param.getEventClickType() == org.bukkit.event.inventory.ClickType.LEFT;
        boolean isRight = param.getEventClickType() == org.bukkit.event.inventory.ClickType.RIGHT;
        boolean buy;
        if ("leftBuyRightSell".equalsIgnoreCase(shopType)) {
            if (!isLeft && !isRight) {
                return true;
            }
            buy = isLeft;
        } else if ("leftSellRightBuy".equalsIgnoreCase(shopType)) {
            if (!isLeft && !isRight) {
                return true;
            }
            buy = isRight;
        } else {
            // 未知类型：配置错误，不静默放行
            player.sendMessage(LangUtil.format("configError", "%reason%", "未知的 shopType: " + shopType));
            return false;
        }

        // 价格与数量解析失败要报错而不是当 0 —— 当 0 会变成免费发放
        Double price = parseAmount(player, param.getShopPrice());
        Integer number = parseNumber(player, param.getShopNumber(), param.getInputValue());
        if (price == null || number == null || number <= 0) {
            return false;
        }

        ItemStack goods = resolveGoods(player, param, buy);
        if (goods == null) {
            return false;
        }

        if (buy) {
            return handleBuy(player, param, goods, price, number);
        }
        return handleSell(player, param, goods, price, number);
    }

    /**
     * 买入：先确认钱够，再扣钱，最后给物品。
     *
     * @param player 玩家
     * @param param  按钮
     * @param goods  商品
     * @param price  单价
     * @param number 数量
     * @return true 表示交易完成
     */
    private boolean handleBuy(Player player, MenuButtonParam param, ItemStack goods, double price, int number) {
        double total = price * number;

        if ("point".equalsIgnoreCase(param.getShopEconomy())) {
            player.sendMessage(LangUtil.message("noLimitItem"));
            return false;
        }

        if (!EconomyUtil.isReady()) {
            player.sendMessage(LangUtil.message("noLimitItem"));
            return false;
        }
        if (EconomyUtil.getBalance(player) < total) {
            player.sendMessage(LangUtil.format("noBalance", "%price%", EconomyUtil.format(total)));
            return false;
        }
        // 余额已确认充足，此时扣款不会失败
        if (!EconomyUtil.withdraw(player, total)) {
            player.sendMessage(LangUtil.message("clickOperatorReason"));
            return false;
        }

        ItemStack give = goods.clone();
        give.setAmount(number);
        ItemStackUtil.give(player, give, number);
        player.sendMessage(LangUtil.format("buyMsg", "%price%", EconomyUtil.format(total)));
        return true;
    }

    /**
     * 卖出：先确认物品够，再扣除物品，最后给钱。
     *
     * @param player 玩家
     * @param param  按钮
     * @param goods  要上交的物品
     * @param price  单价
     * @param number 数量
     * @return true 表示交易完成
     */
    private boolean handleSell(Player player, MenuButtonParam param, ItemStack goods, double price, int number) {
        int owned = countItems(player, goods);
        if (owned < number) {
            player.sendMessage(LangUtil.format("noItem", "%number%", String.valueOf(number - owned)));
            return false;
        }

        removeItems(player, goods, number);

        double total = price * number;
        // 必须检查存款结果：经济插件可能拒绝（账户不存在、插件报错）。
        // 不检查的话物品已经扣掉、钱没到账，玩家还会看到"出售成功"。
        if (!EconomyUtil.deposit(player, total)) {
            // 钱没给成，把物品退回去，不能既扣物品又不给钱
            ItemStack refund = goods.clone();
            refund.setAmount(number);
            ItemStackUtil.give(player, refund, number);
            player.sendMessage(LangUtil.message("clickOperatorReason"));
            return false;
        }
        player.sendMessage(LangUtil.format("sellMsg", "%price%", EconomyUtil.format(total)));
        return true;
    }

    /**
     * 解析商品物品。
     *
     * <p>买入时商品是 {@code shopMaterial}（玩家会得到的东西），
     * 卖出时商品是 {@code shopGiveMaterial}（玩家要交出去的东西）。
     *
     * @param player 玩家
     * @param param  按钮
     * @param buy    是否为买入
     * @return 物品；无法解析时返回 null 并已向玩家报错
     */
    private ItemStack resolveGoods(Player player, MenuButtonParam param, boolean buy) {
        String material = buy ? param.getShopMaterial() : param.getShopGiveMaterial();
        if (TextUtil.isEmpty(material)) {
            player.sendMessage(LangUtil.format("configError", "%reason%",
                    (buy ? "shopMaterial" : "shopGiveMaterial") + " 未配置"));
            return null;
        }

        // 支持 "MATERIAL:数量" 写法，材质名本身可能含冒号，故从最后一个冒号切分
        String name = material;
        int colon = material.lastIndexOf(':');
        if (colon > 0 && colon < material.length() - 1) {
            String tail = material.substring(colon + 1);
            if (TextUtil.isNumeric(tail)) {
                name = material.substring(0, colon);
            }
        }

        ItemStack itemStack = ItemStackUtil.parse(name);
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            player.sendMessage(LangUtil.message("noLimitItem"));
            return null;
        }
        return itemStack;
    }

    /**
     * 解析价格，支持算术表达式。
     *
     * @param player 玩家
     * @param text   配置字符串
     * @return 金额；无法解析时返回 null 并已向玩家报错
     */
    private Double parseAmount(Player player, String text) {
        if (TextUtil.isEmpty(text)) {
            player.sendMessage(LangUtil.format("configError", "%reason%", "shopPrice 未配置"));
            return null;
        }
        String resolved = PlaceholderUtil.set(player, text);
        Double value = TextUtil.evaluate(resolved);
        if (value == null) {
            player.sendMessage(LangUtil.format("configError", "%reason%", "无法解析的价格: " + text));
            return null;
        }
        return value;
    }

    /**
     * 解析数量，支持算术表达式与 {@code %input%}。
     *
     * @param player     玩家
     * @param text       配置字符串
     * @param inputValue 玩家输入值，用于替换 {@code %input%}
     * @return 数量；无法解析时返回 null 并已向玩家报错
     */
    private Integer parseNumber(Player player, String text, String inputValue) {
        if (TextUtil.isEmpty(text)) {
            player.sendMessage(LangUtil.format("configError", "%reason%", "shopNumber 未配置"));
            return null;
        }
        String resolved = PlaceholderUtil.set(player, text)
                .replace("%input%", inputValue == null ? "" : inputValue);
        Double value = TextUtil.evaluate(resolved);
        if (value == null) {
            player.sendMessage(LangUtil.format("configError", "%reason%", "无法解析的数量: " + text));
            return null;
        }
        return (int) Math.round(value);
    }

    /**
     * 统计玩家背包中指定物品的数量。
     *
     * @param player 玩家
     * @param item   目标物品
     * @return 数量
     */
    private int countItems(Player player, ItemStack item) {
        int count = 0;
        for (ItemStack content : player.getInventory().getContents()) {
            if (content != null && content.isSimilar(item)) {
                count += content.getAmount();
            }
        }
        return count;
    }

    /**
     * 从玩家背包移除指定数量的物品。
     *
     * @param player 玩家
     * @param item   目标物品
     * @param amount 数量
     */
    private void removeItems(Player player, ItemStack item, int amount) {
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack content = contents[i];
            if (content == null || !content.isSimilar(item)) {
                continue;
            }
            int take = Math.min(remaining, content.getAmount());
            content.setAmount(content.getAmount() - take);
            remaining -= take;
            if (content.getAmount() <= 0) {
                player.getInventory().setItem(i, null);
            }
        }
    }

    // ------------------------------------------------------------------
    // 限制记录
    // ------------------------------------------------------------------

    /**
     * 记录本次使用。
     *
     * <p>三条独立规则，各自判断：
     * <ul>
     *   <li>{@code limit &gt; 0} 且 {@code action.limit} 开启 → 次数 +1</li>
     *   <li>{@code cd &gt; 0} 且 {@code action.cd} 开启 → 写入当前时间</li>
     *   <li>{@code limitHide} / {@code cdHide} → <b>不受开关约束</b>，配了就记录。
     *       因为隐藏功能本身依赖记录数据，不记录就没有数据可隐藏。</li>
     * </ul>
     *
     * <p>{@code id == 0} 的按钮完全没有记录 —— 这是"临时按钮"的约定。
     *
     * @param player 玩家
     * @param param  按钮
     */
    private void applyLimits(Player player, MenuButtonParam param) {
        if (param.getId() == 0) {
            return;
        }

        // 次数：若点击路径已经用原子门槛占过名额，这里绝不能再加一次，
        // 否则一次点击会被记成两次，限购提前耗尽。
        boolean gateUsed = param.isNumberClaimed();
        boolean needNumber = !gateUsed
                && ((param.getLimit() > 0 && config.isActionLimit()) || param.isLimitHide());
        boolean needTime = (param.getCd() > 0 && config.isActionCd()) || param.isCdHide();
        if (!needNumber && !needTime) {
            return;
        }

        // 写库是阻塞操作，必须异步 —— 在主线程做会在点击时造成明显的卡顿
        SchedulerUtil.runTaskAsynchronously(plugin, () -> {
            if (needTime) {
                limitService.setClickTime(player.getName(), player.getUniqueId(),
                        param.getId(), System.currentTimeMillis());
            }
            if (needNumber) {
                limitService.addNumber(player.getName(), player.getUniqueId(), param.getId(), 1);
            }
        });
    }

    // ------------------------------------------------------------------
    // 限购原子门槛
    // ------------------------------------------------------------------

    /**
     * 原子地占用次数名额，成功后执行动作链。
     *
     * <p>供"延迟执行"的路径（输入完成后、确认界面点确定后）复用：
     * 这些路径拿到的是渲染时或更早的快照，不能用它判断次数，必须现查现占。
     *
     * <p>数据库访问在异步线程完成，动作链回到实体线程执行。
     *
     * @param player 玩家
     * @param param  按钮
     * @param gui    当前界面，可为 null
     */
    public void executeWithLimitGate(Player player, MenuButtonParam param, GuiContext gui) {
        if (player == null || param == null) {
            return;
        }
        boolean gate = param.getId() != 0
                && param.getLimit() > 0
                && config != null
                && config.isActionLimit();
        if (!gate) {
            execute(player, param, null, gui);
            return;
        }

        final int max = param.getLimit();
        SchedulerUtil.runTaskAsynchronously(plugin, () -> {
            boolean granted = limitService.tryIncrementWithinLimit(
                    player.getName(), player.getUniqueId(), param.getId(), max);
            SchedulerUtil.runAtEntity(plugin, player, () -> {
                if (!granted) {
                    playSound(player, param.resolveFailSound());
                    player.sendMessage(LangUtil.message("noLimit"));
                    return;
                }
                param.setNumberClaimed(true);
                MenuLimit fresh = limitService.find(player.getUniqueId(), param.getId());
                execute(player, param, fresh, gui);
            });
        });
    }

    // ------------------------------------------------------------------
    // 动作链
    // ------------------------------------------------------------------

    /**
     * 执行一串动作（无输入值）。
     *
     * @param player  玩家
     * @param actions 动作列表
     * @param gui     当前界面，可为 null
     * @param index   当前处理到的下标
     */
    public void runCommands(Player player, List<String> actions, GuiContext gui, int index) {
        runCommands(player, actions, gui, index, "");
    }

    /**
     * 执行一串动作。
     *
     * <p>递归而非循环，是因为 {@code [delay]} 需要把"剩余的动作"保存下来
     * 交给下一次调度。用循环 + 状态机也能实现，但可读性差很多。
     *
     * <p>输入值作为参数显式逐层传递，<b>不</b>用 {@code ThreadLocal} ——
     * 输入流程会跨线程（聊天线程写入、实体线程执行），线程局部变量传不过去。
     *
     * @param player     玩家
     * @param actions    动作列表
     * @param gui        当前界面，可为 null
     * @param index      当前处理到的下标
     * @param inputValue 玩家输入值，可为空串
     */
    public void runCommands(Player player, List<String> actions, GuiContext gui, int index,
                            String inputValue) {
        if (actions == null || index < 0 || index >= actions.size()) {
            return;
        }
        if (!player.isOnline()) {
            // 玩家中途退出，剩余动作没有意义
            return;
        }

        String raw = actions.get(index);
        if (raw == null || raw.trim().isEmpty()) {
            // 空行是配置错误，而不是"跳过这一行"。静默跳过会让
            // 后面的动作全部失效却毫无线索，故显式报错并终止整条链。
            player.sendMessage(LangUtil.format("configError", "%reason%",
                    "节点 commands 第 " + (index + 1) + " 行出现空行"));
            return;
        }

        CommandTypeEnum type = CommandTypeEnum.match(raw);
        if (type == null) {
            player.sendMessage(LangUtil.format("configError", "%reason%",
                    "第 " + (index + 1) + " 行无法识别的动作: " + raw));
            return;
        }

        String body = CommandTypeEnum.stripTag(raw);
        // 变量替换：PAPI 优先，再替换输入值。顺序不能反 ——
        // 输入值本身可能包含 % 符号，先替换输入会让 PAPI 误解析。
        body = PlaceholderUtil.set(player, body);
        body = body.replace("%input%", inputValue == null ? "" : inputValue);
        body = body.replace("%player%", player.getName());

        switch (type) {
            case MESSAGE:
                player.sendMessage(TextUtil.color(body));
                break;
            case PLAYER:
                player.performCommand(body);
                break;
            case CONSOLE:
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), body);
                break;
            case OP:
                // 临时提权执行，执行完立刻降权。用 try/finally 保证
                // 命令抛异常时权限也会被还原，否则玩家会永久保留 OP。
                boolean wasOp = player.isOp();
                try {
                    player.setOp(true);
                    player.performCommand(body);
                } finally {
                    if (!wasOp) {
                        player.setOp(false);
                    }
                }
                break;
            case DISPATCH_COMMAND:
                Bukkit.dispatchCommand(player, body);
                break;
            case PERFORM_COMMAND:
                player.performCommand(body);
                break;
            case OP_PERFORM_COMMAND:
                // 与 [op] 的区别：这里按原版语义"先执行前置命令，再提权执行本体"。
                // 本体格式为 {@code 前置命令;本体命令}；没有分号时退化为纯提权执行。
                // 早期实现漏了这个 case，导致该标签落到 default 分支并报
                // "未实现的动作" 从而中断整条动作链。
                int semicolon = body.indexOf(';');
                String prerequisite = semicolon < 0 ? "" : body.substring(0, semicolon).trim();
                String actual = semicolon < 0 ? body : body.substring(semicolon + 1).trim();
                boolean opWasOp = player.isOp();
                try {
                    if (!prerequisite.isEmpty()) {
                        player.performCommand(prerequisite);
                    }
                    if (!actual.isEmpty()) {
                        player.setOp(true);
                        player.performCommand(actual);
                    }
                } finally {
                    if (!opWasOp) {
                        player.setOp(false);
                    }
                }
                break;
            case CLOSE:
                if (gui != null) {
                    gui.close();
                }
                break;
            case OPEN:
                openMenu(player, body);
                return;
            case TITLE:
                sendTitle(player, body);
                break;
            case SOUND:
                playSound(player, body);
                break;
            case DELAY:
                scheduleDelay(player, actions, gui, index, body, inputValue);
                // delay 之后由调度器继续，本方法就此结束
                return;
            case MONEY:
                handleMoney(player, body);
                break;
            case POINT:
                player.sendMessage(LangUtil.message("noLimitItem"));
                break;
            case ITEM:
                handleItem(player, body);
                break;
            case ACTION:
                // [action] 是对"再执行一个按钮"的支持，格式：菜单名:按钮id
                handleSubAction(player, body, gui);
                break;
            default:
                player.sendMessage(LangUtil.format("configError", "%reason%", "未实现的动作: " + type.getTag()));
                return;
        }

        runCommands(player, actions, gui, index + 1, inputValue);
    }

    /**
     * 处理 {@code [delay]}：延迟后从下一个动作继续。
     *
     * @param player     玩家
     * @param actions    动作列表
     * @param gui        当前界面
     * @param index      当前下标
     * @param body       延迟时长（tick 或带单位）
     * @param inputValue 玩家输入值，随延迟继续传递
     */
    private void scheduleDelay(Player player, List<String> actions, GuiContext gui, int index,
                               String body, String inputValue) {
        long ticks = parseTicks(body);
        if (ticks <= 0) {
            player.sendMessage(LangUtil.format("configError", "%reason%", "delay 时长非法: " + body));
            return;
        }
        SchedulerUtil.runTaskLater(plugin, () ->
                runCommands(player, actions, gui, index + 1, inputValue), ticks);
    }

    /**
     * 解析延迟 tick 数，支持 {@code 20}、{@code 1s}、{@code 500ms}。
     *
     * @param text 配置文本
     * @return tick 数；无法解析时返回 0
     */
    private long parseTicks(String text) {
        if (TextUtil.isEmpty(text)) {
            return 0L;
        }
        String value = text.trim().toLowerCase();
        try {
            if (value.endsWith("ms")) {
                return Math.max(1L, Long.parseLong(value.substring(0, value.length() - 2).trim()) / 50L);
            }
            if (value.endsWith("s")) {
                return Long.parseLong(value.substring(0, value.length() - 1).trim()) * 20L;
            }
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 发送标题。
     *
     * <p>格式：{@code 主标题;副标题}，副标题可选。
     *
     * @param player 玩家
     * @param body   标题文本
     */
    @SuppressWarnings("deprecation")
    private void sendTitle(Player player, String body) {
        List<String> parts = TextUtil.split(body, ";");
        String title = parts.isEmpty() ? "" : TextUtil.color(parts.get(0));
        String subtitle = parts.size() > 1 ? TextUtil.color(parts.get(1)) : "";
        // 走 title/subtitle 而非 sendTitle 的新签名，是为了兼容 1.8-1.16 的服务端
        player.sendTitle(title, subtitle);
    }

    /**
     * 播放音效。
     *
     * <p>支持 {@code SOUND}、{@code SOUND:音量}、{@code SOUND:音量:音调} 三种写法。
     *
     * @param player 玩家
     * @param text   音效配置
     */
    public void playSound(Player player, String text) {
        if (TextUtil.isEmpty(text) || player == null) {
            return;
        }
        List<String> parts = TextUtil.split(text, ":");
        if (parts.isEmpty()) {
            return;
        }
        String name = parts.get(0).trim().toUpperCase().replace('.', '_');
        float volume = parts.size() > 1 ? parseFloat(parts.get(1), 1F) : 1F;
        float pitch = parts.size() > 2 ? parseFloat(parts.get(2), 1F) : 1F;
        try {
            player.playSound(player.getLocation(), name, volume, pitch);
        } catch (RuntimeException e) {
            // 音效名写错在跨版本时很常见，不应因此中断整个动作链
            plugin.getLogger().fine("无法播放音效: " + name);
        }
    }

    /**
     * 解析浮点数。
     *
     * @param text         文本
     * @param defaultValue 默认值
     * @return 解析结果
     */
    private float parseFloat(String text, float defaultValue) {
        try {
            return Float.parseFloat(text.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 打开另一个菜单。
     *
     * <p>公开是为了让监听器（时钟物品、命令）复用同一套打开流程 ——
     * 权限检查、世界黑名单、渲染都只有这一条路径，避免开出"未经检查"的界面。
     *
     * @param player 玩家
     * @param body   菜单文件名
     * @return true 表示成功打开
     */
    public boolean openMenu(Player player, String body) {
        if (guiOpener == null) {
            return false;
        }
        return guiOpener.open(player, body);
    }

    /**
     * 处理 {@code [money]}：扣钱或给钱。
     *
     * <p>格式：{@code 数量} 表示扣钱，{@code -数量} 表示给钱 —— 用负号区分方向，
     * 与原版保持一致。
     *
     * @param player 玩家
     * @param body   金额
     */
    private void handleMoney(Player player, String body) {
        if (!config.isActionMoney()) {
            return;
        }
        Double amount = TextUtil.evaluate(PlaceholderUtil.set(player, body));
        if (amount == null) {
            player.sendMessage(LangUtil.format("configError", "%reason%", "money 数量非法: " + body));
            return;
        }
        if (amount >= 0) {
            if (!EconomyUtil.withdraw(player, amount)) {
                player.sendMessage(LangUtil.message("noBalance"));
            }
        } else {
            EconomyUtil.deposit(player, -amount);
        }
    }

    /**
     * 处理 {@code [item]}：给物品。
     *
     * <p>格式：{@code 材质:数量}，数量可选。
     *
     * @param player 玩家
     * @param body   物品配置
     */
    private void handleItem(Player player, String body) {
        List<String> parts = TextUtil.split(body, ":");
        if (parts.isEmpty()) {
            return;
        }
        ItemStack itemStack = ItemStackUtil.parse(parts.get(0).trim());
        if (itemStack == null) {
            player.sendMessage(LangUtil.format("configError", "%reason%", "无法解析的物品: " + parts.get(0)));
            return;
        }
        int amount = parts.size() > 1 ? (int) parseFloat(parts.get(1), 1F) : 1;
        ItemStackUtil.give(player, itemStack, Math.max(1, amount));
    }

    /**
     * 处理 {@code [action]}：以当前玩家再执行一个按钮。
     *
     * @param player 玩家
     * @param body   格式 {@code 菜单名:按钮id}
     * @param gui    当前界面
     */
    private void handleSubAction(Player player, String body, GuiContext gui) {
        if (subActionHandler == null) {
            return;
        }
        List<String> parts = TextUtil.split(body, ":");
        if (parts.size() < 2) {
            player.sendMessage(LangUtil.format("configError", "%reason%", "[action] 需要 菜单名:按钮id 格式"));
            return;
        }
        try {
            int id = Integer.parseInt(parts.get(1).trim());
            subActionHandler.run(player, parts.get(0).trim(), id, gui);
        } catch (NumberFormatException e) {
            player.sendMessage(LangUtil.format("configError", "%reason%", "按钮 id 非法: " + parts.get(1)));
        }
    }

    /** 菜单打开回调，由界面层注入，避免核心层依赖界面层。 */
    private GuiOpener guiOpener;

    /** 子动作回调。 */
    private SubActionHandler subActionHandler;

    /**
     * 注入菜单打开回调。
     *
     * @param guiOpener 回调
     */
    public void setGuiOpener(GuiOpener guiOpener) {
        this.guiOpener = guiOpener;
    }

    /**
     * 注入子动作回调。
     *
     * @param handler 回调
     */
    public void setSubActionHandler(SubActionHandler handler) {
        this.subActionHandler = handler;
    }

    /** 打开菜单的回调。 */
    public interface GuiOpener {
        /**
         * 打开菜单。
         *
         * @param player 玩家
         * @param menu   菜单文件名
         * @return true 表示成功打开
         */
        boolean open(Player player, String menu);
    }

    /** 执行子按钮的回调。 */
    public interface SubActionHandler {
        /**
         * 执行子按钮。
         *
         * @param player 玩家
         * @param menu   菜单文件名
         * @param id     按钮 id
         * @param gui    来源界面
         */
        void run(Player player, String menu, int id, GuiContext gui);
    }

    /**
     * 界面上下文。
     *
     * <p>核心层不能依赖具体的 GUI 实现（IFramework 接口很宽），因此只暴露
     * 执行器真正需要的两件事：关闭、刷新。
     */
    public interface GuiContext {
        /** 关闭界面。 */
        void close();

        /** 重新渲染界面。 */
        void refresh();
    }

    /**
     * 解析并校验 {@code %input%} 的存在性。
     *
     * @param actions 动作列表
     * @return true 表示用到了输入值
     */
    public static boolean needsInput(List<String> actions) {
        if (actions == null) {
            return false;
        }
        for (String action : actions) {
            if (action != null && action.contains("%input%")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 便捷方法：按 id 直接记录一次使用。
     *
     * <p>供确认界面的"确认"按钮调用 —— 那条路径走的是自己的流程，
     * 不复用 {@link #execute}。
     *
     * @param player 玩家
     * @param param  按钮
     */
    public void recordUsage(Player player, MenuButtonParam param) {
        applyLimits(player, param);
    }

    /**
     * 调试用：列出全部已知动作标签。
     *
     * @return 标签列表
     */
    public static List<String> knownTags() {
        return Arrays.asList(
                "[message]", "[player]", "[console]", "[op]", "[opPerformCommand]",
                "[performCommand]", "[dispatchCommand]", "[close]", "[open]", "[title]",
                "[sound]", "[delay]", "[money]", "[point]", "[item]", "[action]");
    }
}
