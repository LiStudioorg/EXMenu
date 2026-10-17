# EXMenu 架构文档

> 面向**开发者 / 维护者**。描述重写版插件的分层、数据流、线程模型与扩展点。
>
> 配置与指令的**使用者**文档见
> [CONFIGURATION.md](CONFIGURATION.md) 与 [COMMANDS.md](COMMANDS.md)。

---

## 1. 这是什么

EXMenu 是 PlayerMenu v1.11.0 的**重写实现**：保持 YAML 配置语义与用户使用方式，
但去掉了 HandyLib 依赖，改为只依赖公开 Maven 仓库的库。

| 维度 | 旧实现 (PlayerMenu) | 重写版 (EXMenu) |
|---|---|---|
| 构建 | Maven + **私有仓库**（HandyLib / HandyBom） | Maven + 全公开仓库 |
| 框架 | HandyLib 3.41.6 注解扫描 | 原生 `JavaPlugin` + `plugin.yml` |
| 渲染 | HandyInventory（HandyLib 内置） | **IFramework**（`com.github.stefvanschie.inventoryframework`） |
| 跨版本材质 | ItemCompatLib | **XSeries** |
| NBT | HandyLib 封装 | **item-nbt-api** |
| 存储 | HandyLib `Db` 链式 API | **原生 JDBC + HikariCP** |
| 源码规模 | 61 文件 / 211 KB | 35 文件 / 325 KB（含更多注释） |

**产物**：`newgen/target/EXMenu-1.0.0.jar`（约 16 MB，已 shade 并重定位）。

---

## 2. 分层架构

```
                    ┌─────────────────────────────────────┐
   presentation     │  command/MainCommand                │  14 个子命令
                    │  listener/{ChatInput,ClockInteract, │  3 个监听器
                    │            PlayerQuit}              │
                    └───────────────┬─────────────────────┘
                                    │
                    ┌───────────────▼─────────────────────┐
   render           │  inventory/MenuGuiManager           │  渲染 + 点击回调
                    │  inventory/ConfirmGuiManager        │  二次确认界面
                    │  inventory/ButtonParser             │  YAML → 模型
                    └───────────────┬─────────────────────┘
                                    │
                    ┌───────────────▼─────────────────────┐
   engine           │  core/MenuChecker                   │  五关校验
                    │  core/MenuExecutor                  │  动作链 + 商店 + 经济
                    │  core/ConditionEvaluator            │  条件表达式
                    └───────────────┬─────────────────────┘
                                    │
                    ┌───────────────▼─────────────────────┐
   persistence      │  service/{MenuItem,MenuLimit}Service│  DAO
                    │  storage/{Sqlite,Mysql}Storage      │  方言差异
                    └───────────────┬─────────────────────┘
                                    │
                    ┌───────────────▼─────────────────────┐
   model            │  param/MenuButtonParam (50 字段)    │
                    │  enter/{MenuItem,MenuLimit}         │
                    └─────────────────────────────────────┘

   横切关注点：util/{Scheduler,Text,ItemStack,Lang,Yaml,Economy,Placeholder}Util
              config/MenuConfig（配置快照）  MenuPlugin（装配与接线）
```

### 依赖方向

严格单向，**不允许反向**：

- `core` 不认识 IFramework —— 它通过 `MenuExecutor.GuiContext`（只有 `close()` / `refresh()`）
  与 `GuiOpener` 接口访问界面层。
- `core` 不认识 `MenuConfig` 全量 —— 只依赖 `MenuChecker.ConfigAccessor`（4 个开关）。
- `inventory` 不认识具体 DAO 实现 —— `MenuChecker.LimitView` 收敛为只读视图。

**这样做的收益**：换掉 GUI 库只需要改 `inventory` 包；换掉数据库只需要改 `storage` 包。

---

## 3. 启动流程

`MenuPlugin.onEnable()` 的顺序**有硬性依赖，不可调整**：

| 步骤 | 动作 | 失败处理 |
|---|---|---|
| 1 | `MenuConfig.load(this)` | 记 SEVERE 并 `disablePlugin` |
| 2 | `initStorage()` → `storage.init()` | 记 SEVERE 并 `disablePlugin` |
| 3 | `ItemStackUtil.init()` / `EconomyUtil.init()` / `PlaceholderUtil.init()` | 均**优雅降级**，不致命 |
| 4 | 构造 `MenuItemService` / `MenuLimitService` / `MenuExecutor` / `MenuGuiManager` | — |
| 5 | 接线：`setGuiOpener` / `setSubActionHandler` / `InputManager.*Holder.set` | — |
| 6 | 注册命令与监听器 | 命令缺失仅告警（见下） |

**为什么存储失败必须禁用**：带着坏掉的数据库继续跑，只会在玩家点按钮时抛一堆异常，
且现象是"按钮没反应"，排查成本远高于启动时明确禁用。

**为什么第 5 步不能漏**：`GuiOpener` 与 `SubActionHandler` 是双向回调，漏接的表现是
"点了按钮没反应"且**不报任何错**，极难排查。

`onDisable()` 只做一件事：关闭连接池。

---

## 4. 配置快照与热重载

`MenuConfig` 是**不可变快照**：启动或 `/exmenu reload` 时从磁盘重建整个对象，
之后所有代码只读它，不做增量更新（配置项之间存在耦合，整体替换更安全）。

**reload 的实现要点**：`MenuConfig.reload(plugin)` 内部调用 `load(plugin)` 造一个新对象，
然后**把新值逐个赋给当前实例的字段**，而**不是**替换引用。原因：调用方通过
`ConfigProvider` 接口持有引用，无法把新对象塞回去。字段赋值集中在最后统一进行，
中途抛异常时旧配置仍完整可用，不会出现"配置读了一半"的半残状态。

**`/exmenu reload` 还需要丢弃按钮缓存** —— 见 `MenuGuiManager.invalidateCache()`。
只刷新配置而不丢缓存，玩家会一直看到旧按钮直到重连。

> ⚠️ `storage.yml` **不支持**热重载，改动需重启服务器 —— 连接池一旦建立，
> 中途切换方言会让已缓存的 PreparedStatement 失效。

---

## 5. 渲染管线

`MenuGuiManager.open(player, menuName)`：

```
1. 取菜单配置          config.getMenu(name)      不存在 → 提示 noMenu
2. 权限检查            checkPermission()
3. 世界检查            isWorldBlocked()          命中 noWorld → 拒绝
4. 规整 size           normalizeSize()           9~54 且为 9 的倍数
5. 解析按钮            parseButtons()            带缓存，按 menuFile 键
6. ★ 批量取数据        loadItemLibrary()         一次 IN 查询
                       limitService.findMapByPlayerUuid()  一次查询
7. 逐按钮求值          权限过滤 → 隐藏条件 → 渲染
8. 装配                StaticPane + GuiItem
9. gui.show(player)
```

### 性能关键：第 6 步

一个菜单可能有 54 个按钮。**逐个查库就是 54 次往返**，因此拆成两次批量查询：

- `MenuItemService.findMapByIds(List<Integer>)` —— 物品库条目
- `MenuLimitService.findMapByPlayerUuid(UUID)` —— 该玩家的全部限制记录

这两次查询把渲染路径的数据库往返从 **O(n) 降到 O(1)**。

### 槽位冲突

同一槽位被多个按钮占用时，按 `priority` 决定胜者（高优先级覆盖低优先级），
`slotPriority` 映射记录当前占用者。

### 按钮缓存与「串味」防护

`parseButtons` 的结果按菜单文件名缓存，**缓存对象是共享的**。因此点击回调里
必须先 `param.copy()` 再 `setEventClickType(event.getClick())` ——
直接改缓存对象会把点击类型"串"到其他玩家的渲染上。

---

## 6. 点击执行链

```
GuiItem 点击回调 (MenuGuiManager.createGuiItem)
  ├─ event.setCancelled(true)                    阻止物品被拿走
  ├─ clickParam = param.copy(); setEventClickType(点击类型)
  ├─ 重新检查 MenuChecker.check()                渲染到点击之间状态可能已变
  │    └─ 非 ALLOWED → 播 failSound + 按原因提示 → return
  ├─ 有 input？      → InputManager.begin() + 关界面 → return
  ├─ 需 confirm？    → ConfirmGuiManager.open()      → return
  └─ executor.execute()
       ├─ 阶段1 前置校验 handleShopBefore()   失败 → 播 failSound，★ 不记录任何状态
       ├─ 阶段2 记录使用 applyLimits()        次数 +1 / 冷却起点
       ├─ 阶段3 成功音效 playSound()
       ├─ 阶段4 动作链 runCommands(index=0)
       │         └─ 再按点击类型追加 actions.left/right/middle
       └─ 收尾 isClose() → gui.close()
```

### ★ 为什么「先校验、后记录」是必须的

旧实现先扣限制再做商店校验，导致玩家点一个**余额不足**的商店按钮也会被扣掉次数。
重写版把校验全部前置：**校验失败时立即返回，不写任何使用记录**。

### 五关校验（`MenuChecker.check`）

**顺序不可调换** —— 最便宜的检查放前面，最贵的放最后：

| 顺序 | 检查 | 失败结果 | 备注 |
|---|---|---|---|
| 1 | 次数限制 | `LIMIT_REACHED` | `limit > 0 && action.limit` |
| 2 | 冷却 | `COOLING_DOWN` | `cd > 0 && action.cd` |
| 3 | 点击类型 | `WRONG_CLICK_TYPE` | 静默忽略，不提示 |
| 4 | 自定义条件 | `CONDITION_FAILED` | **最贵**（要跑 PAPI），放最后 |

返回**枚举而非 boolean** 是刻意设计：不同拒绝原因要播不同音效、发不同消息。
旧实现只返回 boolean，导致所有失败都只能给一句笼统的"操作未完成"。

`MenuChecker` **不修改任何状态** —— 记录点击是 `MenuExecutor` 的职责。

---

## 7. 动作链

`runCommands(player, actions, gui, index)` 是**递归**而非循环，因为 `[delay]`
需要把"剩余动作"交给下一次调度。

### 变量替换顺序

```java
body = PlaceholderUtil.set(player, body);   // 1. PAPI 优先
body = body.replace("%input%", getInput()); // 2. 再替换输入值
body = body.replace("%player%", player.getName());
```

**顺序不能反** —— 输入值本身可能包含 `%` 符号，先替换输入会让 PAPI 误解析。

### 终止条件

| 情况 | 行为 |
|---|---|
| 空行 | **报错并终止整条链**（不静默跳过） |
| 无法识别的标签 | 报错并终止整条链 |
| 玩家中途退出 | 静默终止（剩余动作无意义） |
| `[delay]` | 挂到调度器后 `return`，不做 `index+1` |
| `[open]` | 打开新菜单后 `return` |

**空行为何必须报错**：静默跳过会让后面的动作全部失效却毫无线索。

### 标签最长匹配

`CommandTypeEnum.match()` 采用**最长匹配**，不是首次匹配。
`[opPerformCommand]` 的前缀与 `[op]` 相同，按声明顺序取首个命中会让它被 `[op]` 抢走，
导致该标签完全不可用（这是旧实现的真实缺陷）。

---

## 8. 条件表达式

格式：`左侧 运算符 右侧`，两侧均支持 PAPI 变量（在求值**之前**替换）。

| 运算符 | 语义 |
|---|---|
| `>=` | 左 ≥ 右 |
| `<=` | 左 ≤ 右 |
| `!=` | 左 ≠ 右 |
| `==` / `=` | 左 = 右 |
| `>` | 左 > 右 |
| `<` | 左 < 右 |
| 无运算符 | 非空、非 `false`、非 `0` 即成立 |

**两条铁律**（各自对应一个旧实现的真实缺陷）：

1. **双字符运算符必须先于单字符匹配。** 若 `>` 先匹配，`>=` 会被当成 `>`，
   右边留下 `= xxx` 解析成 0 —— 于是 `level >= 10` 退化成 `level > 0`，
   **几乎所有玩家都通过**。因此顺序是 `>=` → `<=` → `!=` → `==` → `=` → `>` → `<`。

2. **数值解析失败必须视为"不满足"，而非"等于 0"。** PAPI 变量不存在时替换结果是
   原样的 `%xxx%`；按 0 参与比较会让 `%xxx% <= 0` 意外成立。因此 `parseNumber()`
   返回 `null` 而不是 0，且显式拒绝 `%` 包裹的字符串。

---

## 9. 线程模型（Spigot / Folia）

`SchedulerUtil` 在**类加载时探测一次**运行环境：

```java
Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
```

比读版本号字符串可靠，且不受服务端品牌影响。

| 方法 | Bukkit | Folia |
|---|---|---|
| `runTask` | `getScheduler().runTask` | `getGlobalRegionScheduler().run` |
| `runTaskLater` | `runTaskLater` | `getGlobalRegionScheduler().runDelayed` |
| `runTaskAsynchronously` | `runTaskAsynchronously` | `getAsyncScheduler().runNow` |
| `runTaskLaterAsynchronously` | tick 换算 | 毫秒（`TimeUnit.MILLISECONDS`） |
| `runAtEntity` | 退化为主线程 | `entity.getScheduler().run(plugin, ...)` |
| `cancelTasks` | `cancelTasks` | **空操作**（Folia 无等价入口） |

### 必须遵守的规则

- **数据库读写一律异步**（`runTaskAsynchronously`）
- **玩家背包 / 实体属性 / `openInventory` 一律回玩家线程**（`runAtEntity`）
- **禁止直接使用 `Bukkit.getScheduler()`** —— Folia 下不安全

### `GuiContext` 的调度要求

`MenuGuiManager.wrap(plugin, gui, player, param)` 必须传入真实的 `plugin`：
`Bukkit.getScheduler().runTask(null, task)` 会抛 NPE。因此对外统一使用实例方法
`wrapFor(...)`（内部已固定 `plugin`），静态重载仅供无插件实例的场景兜底。

---

## 10. 按钮属性全表（YAML 键 → 字段）

`ButtonParser.parse()` 是配置与代码之间的**唯一转换点**。解析遵循**宽容原则**：
单个字段写错回退默认值，但结构性错误（无 `slots`）会让按钮被丢弃。

| YAML 键 | 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|---|
| `slot` / `slots` | `slots` | int / list | — | **必需**，无则按钮被丢弃 |
| `material` | `material` | str | `STONE` | 支持跨版本写法 |
| `name` | `name` | str | — | 支持颜色与 PAPI |
| `lore` | `lore` | list | `[]` | |
| `amount` | `amount` | int | `1` | 最小 1 |
| `dynamicName` | `dynamicName` | str | — | 每次渲染重新求值 |
| `dynamicLore` | `dynamicLore` | list | `[]` | |
| `dynamicAmount` | `dynamicAmount` | str | — | |
| `enchant` | `enchant` | bool | `false` | 附魔光效 |
| `hideFlag` | `hideFlag` | bool | **`true`** | |
| `hideEnchant` | `hideEnchant` | bool | **`true`** | |
| `unbreakable` | `unbreakable` | bool | — | 未配置则不设置 |
| `customModelData` | `customModelData` | int | `0` | |
| `tooltipStyle` | `tooltipStyle` | str | — | 1.21.1+ |
| `head` / `headBase` | `head` / `headBase` | str | — | 头颅材质 |
| `id` | `id` | int | `0` | **双语义**，必须全局唯一 |
| `commands` | `commands` | list | `[]` | 动作链 |
| `actions.left/right/middle` | `actionsLeft/Right/Middle` | list | `[]` | 分键动作链 |
| `close` | `close` | bool | `false` | 执行后关界面 |
| `openItem` | `openItem` | str | — | |
| `priority` | `priority` | int | `0` | 同槽位覆盖优先级 |
| `sound` / `failSound` | `sound` / `failSound` | str | — | |
| `permission` | `permission` | list | `[]` | 需**全部**满足 |
| `notPermission` | `notPermission` | list | `[]` | 任一命中则隐藏 |
| `conditions` | `conditions` | list | `[]` | 需全部满足才可点 |
| `conditionsHide` | `conditionsHide` | list | `[]` | 任一命中则隐藏 |
| `cd` | `cd` | ms | `0` | 支持 `5s` / `2m` / `1h` |
| `cdHide` | `cdHide` | bool | `false` | |
| `cdMsg` | `cdMsg` | str | — | |
| `limit` | `limit` | int | `0` | 0 = 无限制 |
| `limitHide` | `limitHide` | bool | `false` | |
| `limitMsg` | `limitMsg` | str | — | |
| `confirm` | `confirm` | bool | `false` | 二次确认 |
| `input` | `input` | str | — | 非空即触发输入流程 |
| `inputType` | `inputType` | str | 取 `input` 值 | `TEXT`/`NUMBER`/`POSITIVE_NUMBER` |
| `shopType` | `shopType` | str | — | `buy`/`sell`/`leftBuyRightSell`/`leftSellRightBuy` |
| `shopPrice` / `shopNumber` | 同名 | str | — | 支持公式 |
| `shopMaterial` / `shopGiveMaterial` | 同名 | str | — | `材质:数量` |
| `shopEconomy` | `shopEconomy` | str | `money` | |

**`cd` 的单位宽容度是刻意加的**：旧版只接受裸毫秒数字，配置者写 `5s` 会被解析成
**5 毫秒**，冷却形同虚设且极难排查。现在接受 `s`/`秒`、`m`/`分`、`h`/`时` 后缀。

**`input` 与 `inputType` 是两个键**：`input` 既可以是类型名（`NUMBER`）也可以是提示文本；
类型未显式指定时，取 `input` 自身的值去解析。

---

## 11. 数据模型与持久化

### 表结构

**`menu_item`** —— 物品库

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | 自增主键 | 即按钮里的 `id`（正数） |
| `menu_name` | VARCHAR(64) | 添加时所在菜单，仅追溯用 |
| `item_md5` | VARCHAR(64) | **唯一**，去重依据 |
| `item_json` | TEXT | base64 序列化物品 |
| `create_time` | BIGINT | 毫秒时间戳 |

**`menu_limit`** —— 次数与冷却记录

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | 自增主键 | |
| `player_name` | VARCHAR(64) | |
| `player_uuid` | VARCHAR(64) | |
| `menu_item_id` | INT | 按钮 `id` |
| `number` | INT | 已使用次数 |
| `click_time` | BIGINT | 上次点击时间 |
| — | **唯一索引** | `(player_uuid, menu_item_id)` |

### 方言差异（`Storage` 接口收敛）

| | SQLite | MySQL |
|---|---|---|
| 主键 | `INTEGER PRIMARY KEY AUTOINCREMENT` | `INT NOT NULL AUTO_INCREMENT PRIMARY KEY` |
| 唯一索引 | `UNIQUE (...)` | `UNIQUE KEY uk_xxx (...)` |
| 建表后缀 | — | `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4` |
| 连接池上限 | **1**（刻意的） | 默认 8 |

**SQLite 池上限设为 1 是刻意的**：SQLite 写锁是库级的，多写连接只会互相阻塞并提高
`SQLITE_BUSY` 概率，串行化反而更快更稳。同时开启 `journal_mode=WAL`（允许读写并发，
降低开菜单卡顿）与 `busy_timeout=5000`（遇锁等待而非立即失败）。

### 竞态消除

`MenuLimitService` **刻意避开"先查再写"模式**。旧实现两个并发点击都读到 `number = 4`、
都写回 `5`，于是限制 5 次的按钮实际被点了 6 次。重写版改用两条原子路径：

- **累加**：`UPDATE ... SET number = number + ?` —— 由数据库保证读改写原子性
- **首次写入**：`INSERT` 撞唯一索引后回退 `UPDATE`，不依赖任何前置查询

### 渲染路径的批量查询

`findMapByPlayerUuid(UUID)` 与 `findMapByIds(List<Integer>)` 是专门为渲染路径加的，
用于消除 N+1（见 §5）。

---

## 12. 输入流程

```
点击带 input 的按钮
  → InputSession(param, sourceGui) 存入 InputManager（ConcurrentHashMap）
  → 关界面 + 提示（inputPrompt / inputExit）
  → 注册超时任务（config.inputTimeout 秒）

AsyncPlayerChatEvent (LOWEST, ignoreCancelled=true)
  → 不在等待中 → 忽略
  → ★ 先取消事件（输入内容不该被广播）
  → 含 % → 拒绝（noInput）
  → 内容 == "T"（大写）→ 退出输入并重开原界面
  → InputTypeEnum 校验（number / number+）
  → MenuExecutor.setInput(message)
  → 回玩家线程：confirm → 确认界面；否则 executor.execute()
  → finally: setInput(null)   ← 用完即清，避免泄漏到下一个动作链
```

**为什么优先级是 `LOWEST`**：要在其他插件（聊天格式、禁言、聊天过滤）**之前**判断并取消。
排在后面的话，被"禁言插件"取消的消息永远进不了输入流程，玩家会觉得输入框没反应。

**为什么用 `ThreadLocal` 传输入值**：输入是"玩家打完字后立刻执行按钮"的流程，中间不跨线程。
`clearInput()` 是必要的 —— 服务端线程池会复用线程，不清除的话下一个在这个线程上执行的
动作可能读到上一个玩家残留的值。

**超时兜底**：玩家开了输入却一直不说话，状态会永久残留，导致他之后发的每句话都被吃掉。

---

## 13. 权限模型

权限节点统一为 `exmenu.<子命令>`，集中在 `MainCommand.PERMISSIONS` 表：

| 子命令 | 权限 | 默认 |
|---|---|---|
| `open` | `exmenu.open` | 所有玩家 |
| `close` | `exmenu.close` | 所有玩家 |
| `clock` | `exmenu.clock` | 所有玩家 |
| `reload` | `exmenu.reload` | OP |
| `create` / `view` | `exmenu.create` / `.view` | OP |
| `item` / `addItem` / `getItem` | 同名 | OP |
| `getMaterial` / `clear` / `convert` | 同名 | OP |
| `adminOpen` | `exmenu.adminOpen` | OP |
| `check` | `exmenu.check` | OP |

`hasPerm()` 在控制台发送者下**直接放行**，并额外接受 `exmenu.*` 通配。

**菜单级权限**：`menu/*.yml` 的 `permission` 节点，`config.yml` 的 `permission: true`
时生效。

---

## 14. 与旧实现的语义差异（有意为之）

| 项 | 旧实现 | 重写版 | 理由 |
|---|---|---|---|
| `[opPerformCommand]` | 被 `[op]` 抢占，**不可用** | 最长匹配，**可用** | 修缺陷 |
| 商店校验时机 | 先扣款后验物品 | **先验后扣** | 避免扣钱不给货 |
| 限制记录时机 | 校验前 | **校验通过后** | 避免失败也扣次数 |
| 条件数值解析失败 | 当 0（可误放行） | **视为不满足** | 保守拒绝 |
| 点击被拒提示 | 统一"操作未完成" | **按原因区分** | 可排查 |
| 输入容器 | 非并发 Map | `ConcurrentHashMap` | 线程安全 |
| 限制写库 | 读后写（有竞态） | **原子 UPDATE** | 修缺陷 |
| 裸 `/exmenu` 帮助 | 被 `reload` 权限硬编码拦截 | **无需权限** | 普通玩家能看到"没权限"而非"未知命令" |
| `[close]` 的 NPE | 确认界面下 `handyInventory == null` 崩溃 | 空值保护 | 修缺陷 |
| 硬编码中文 | 命令类里有 | 全部走 `LangUtil` | 可本地化 |

---

## 15. 已知缺陷与未完成项

本节记录**经代码审计确认**的真实缺口。分两类：**已修复**（保留记录以便回溯）与**未完成**。

### 15.1 已修复

| 问题 | 影响 | 修复 |
|---|---|---|
| `action.*` 用 `getBoolean()` 读取，而配置写的是 `ALL` | **开箱即用时冷却、次数限制、经济动作全部被静默禁用** | 改为 `isActionEnabled()`，只有显式 `false/off/no/0` 才关闭 |
| `[actionbar]` 不是任何枚举常量 | 该行报 configError 并**中断整条动作链**，后续 `[sound]` 不执行 | 从 `example.yml` 移除；`CommandTypeEnum` 无此标签 |
| `[opPerformCommand]` 缺 `case` | 落到 `default` → configError → **中断动作链** | 补 `OP_PERFORM_COMMAND` 分支（`前置命令;本体` 格式） |
| `/exmenu reload` 不丢按钮缓存 | 重载后玩家**仍看到旧按钮**直到重连 | `handleReload` 显式调用 `invalidateCache()` |
| `GuiContext.close()/refresh()` 传 null plugin | `Bukkit.getScheduler().runTask(null, …)` **NPE** | 新增 `wrapFor()` 实例方法固定 plugin；静态重载回退同步执行 |
| 示例菜单条件 `'%player_name% != '` 右侧为空 | 条件恒不满足 → **按钮对所有人隐藏** | 改为 `!= 无`，并在注释中说明两侧都必须有值 |
| **限购闸门"读后判断"** | 两个并发点击（或同一界面连点）都读到旧值而双双放行，`limit: 5` 可被用 6 次以上 | 新增 `MenuLimitService.tryIncrementWithinLimit()`，用 `UPDATE ... WHERE number < ?` 由数据库原子裁决；三条执行路径（直接点击、输入完成、确认界面）统一走该门槛 |
| **`%input%` 用 `ThreadLocal` 跨线程传递** | 聊天线程写入、实体线程读取，值传不过去 → **`%input%` 恒为空**；且写入侧残留在池化线程上会跨玩家泄漏 | 输入值改挂在 `MenuButtonParam.inputValue` 上随参数传递；`runCommands` 增加 `inputValue` 形参逐层下传 |
| **确认界面执行时传 `null` 限购记录** | `confirm: true` 的按钮被当成"从未使用"，**可无限点击** | 确认路径改走 `executeWithLimitGate()`，现查现占 |
| **`pane` 配置节被当字符串读取** | `getString("pane")` 对配置节返回 null → **菜单级装饰板从未生效**，`enable`/`slots` 全是摆设 | 新增 `applyPane()`，按配置节解析 `material`/`name`/`slots`/`enable`，并保留旧式平铺字符串写法 |
| **卖出成功不看存款结果** | 经济插件拒绝时**物品已扣、钱没到账**，还提示"出售成功" | 检查 `EconomyUtil.deposit()` 返回值，失败则退还物品并提示 |
| **`onDisable` 不取消调度任务** | `[delay]` 续跑与输入超时任务在插件卸载后仍触发，且连接池已关闭 | `onDisable` 先 `SchedulerUtil.cancelTasks(this)` 再关存储 |
| **`MenuItemService.save()` 先查后写** | 并发插入时败者返回 null，玩家看到"存储失败"（物品其实已入库） | 改为"先插后查"：插入成功即完成，撞唯一索引再查回既有 id |

### 15.2 未完成（功能缺口）

1. **`[point]` 未实现** —— `MenuExecutor` 的 `POINT` 分支只发一句 `noLimitItem`，
   没有真正给点券；`shopEconomy: point` 同样被拒。
2. **`[performCommand]` 与 `[player]` 行为完全相同** —— 二者都走
   `player.performCommand(body)`，不存在"绕过权限"的机制。
3. **`gui/item.yml` 已加载但无人消费** —— 物品库浏览界面尚未接线。
4. **`openCommand` / `openItem` 不生效** —— `MenuConfig.commandMap` / `itemMap`
   已填充但**无任何监听器消费**；缺少 `PlayerCommandPreprocessEvent` 监听器，
   也缺少手持物品右键的判定。`example.yml` 里"输入 `/mc` 打开"与
   "手持时钟右键打开"目前是**惰性的**。
   > 注：`clock.menu` 配置的时钟物品由 `ClockInteractListener` 正常处理，
   > 不受此影响。
5. **Shift+F 未实现** —— `config.yml` 的 `shift.F.enable/menu` 与
   `MenuConfig.isShiftEnable()/getShiftMenu()` 存在但无消费者。
6. **`GuiTypeEnum` 整个类未被使用** —— 图形化创建/编辑菜单流程未实现，
   `/exmenu create` 只生成骨架文件。
7. **`EconomyUtil` 只支持 Vault** —— 未接入 PlayerPoints / PlayerCurrency。
8. **`hideEnchant`、`head`、`headBase`、`openItem` 解析后无渲染效果** ——
   `ItemStackUtil.applyHeadTexture()` 从未被调用。
9. **`shopType` 的 `buy` / `sell` 未实现** —— 只有 `leftBuyRightSell` /
   `leftSellRightBuy` 有效，其它值报 configError 并阻止点击。
10. **`ConditionEvaluator` 不支持 `!` 取反与 AND/OR** —— 前导 `!` 会被当作
    "开关式条件"判为真；`a >= b >= c` 会拿 `a` 与字符串 `"b >= c"` 比较。
11. **`config.yml` 的 `isCheckUpdate` 无更新检查器**；`autoCreateId` 被
    `handleCreate` 忽略（骨架硬编码）。
12. **`Storage.init()` 文档称幂等，实际每次都会新建连接池** —— 重复调用会泄漏前一个池。
13. **未在真实服务端验证** —— 交付前仅通过编译、字节码链接与 YAML 结构校验。

### 15.3 死代码

审计确认以下成员**无任何调用点**，属于设计残留：

- 整个类：`constants/GuiTypeEnum`
- 方法：`MenuGuiManager.openAsPlayer` /
  `getSize` / `refresh(Player)`、`MenuExecutor.needsInput` / `recordUsage` /
  `knownTags`、`MenuChecker.LimitView`、`MenuButtonParam.hasLimit` /
  `isPersistent`、`MenuItemService.emptyMap`、`SqliteStorage.warn`、
  `ItemStackUtil.applyHeadTexture`、
  `YamlUtil.getStringList` / `getSection` / `loadDirectory` 等
- `pom.xml` 声明了 `item-nbt-api` 与 `bstats`，但**没有任何源文件引用**

> 这些不影响功能，但会误导后续维护者。建议在下一轮清理中移除，
> 或补上实现（尤其是 `openCommand` / `openItem` 与物品库界面）。
>
> 已清理：`MenuPlugin.reloadPluginConfig`（逻辑与 `MainCommand.handleReload` 重复）
> 与 `InputManager.cleanup`（被 `PlayerQuitListener` 经 `cancel` 覆盖）已删除；
> `ClockInteractListener.cleanup` 由死代码改为在玩家退出时实际调用。

---

## 16. 扩展指南

### 新增一个动作标签

1. 在 `constants/CommandTypeEnum.java` 增加枚举常量（标签 + 中文说明）。
2. 在 `core/MenuExecutor.runCommands()` 的 `switch` 里增加 `case`。
3. 在 `menu/example.yml` 或文档中补充用法示例。

> 标签**不要**写成其他标签的前缀（如已有 `op`，就不要加 `opXxx` 依赖首次匹配）；
> 虽然 `match()` 已用最长匹配，但语义重叠仍会让配置者困惑。

### 新增一个子命令

1. 在 `command/MainCommand.PERMISSIONS` 注册 `子命令 → 权限节点`。
2. 在 `onCommand` 的 `switch` 增加分支，实现 `handleXxx`。
3. 在 `plugin.yml` 声明对应权限节点。
4. 在 `sendHelp()` 与 `onTabComplete` 补上。

### 新增一个存储后端

实现 `storage/Storage` 接口（6 个方法），在 `MenuPlugin.initStorage()` 的分支里注册。
方言差异全部通过 `autoIncrementPrimaryKey()` 与 `createTableIfNotExists()` 收敛，
**不要在 DAO 里写方言判断**。

### 新增一个按钮属性

1. `param/MenuButtonParam.java` 加字段 + getter/setter + `copy()` 同步。
2. `inventory/ButtonParser.java` 加解析。
3. 若参与校验，在 `MenuChecker` 加分支。

> ⚠️ `MenuButtonParam.copy()` 必须同步新字段，否则点击时复制的副本会丢失该属性 ——
> 这是最容易被漏掉的一步。

---

## 17. 构建

```bash
cd newgen
mvn clean package
```

产物：`newgen/target/EXMenu-1.0.0.jar`

- 依赖全部来自公开仓库（papermc / codemc / spigot / extendedclip / jitpack）
- **无需任何私有仓库凭据**
- 目标字节码：**Java 17**（`folia-api` 1.21.4 的要求）
- 仓库根目录的聚合构建见 [README.md](README.md)

### shade 与重定位

`maven-shade-plugin` 把若干库打进 jar 并重定位，避免与其他插件的同库不同版本冲突：

| 原包 | 重定位后 |
|---|---|
| `com.github.stefvanschie.inventoryframework` | `cn.listudio.exmenu.lib.if` |
| `com.cryptomorin.xseries` | `cn.listudio.exmenu.lib.xseries` |
| `de.tr7zw.changeme.nbtapi` | `cn.listudio.exmenu.lib.nbt` |
| `de.tr7zw.annotations` | `cn.listudio.exmenu.lib.nbt.annotations` |

同时排除 `META-INF/*.SF`、`*.DSA`、`*.RSA`、`MANIFEST.MF`、`module-info.class`
（签名文件会让重定位后的 jar 校验失败）。

`sqlite-jdbc` / `mysql-connector-java` / `HikariCP` / `gson` **不重定位**（这些是
基础库，重定位会破坏 JDBC 驱动发现机制）。

> **重定位的后果**：源码 import 的是原始包名，编译期正常，
> 但**运行期**这些类在 `cn.listudio.exmenu.lib.*` 下。
> 新增代码若用反射按字符串查找它们，必须写重定位后的名字。

`plugin.yml` 参与 Maven 资源过滤（可用 `${project.version}`），
其余资源不参与过滤 —— 否则菜单与语言文件里的 `%player_name%` 和 `&a`
会被 Maven 当作占位符处理。

---

## 18. 类速查

| 类 | 职责 | 关键点 |
|---|---|---|
| `MenuPlugin` | 装配与接线 | 启动顺序不可调；同时是 `ConfigProvider` |
| `MenuConfig` | 配置快照 | 整体重建；`reload` 就地刷新字段 |
| `MainCommand` | 14 个子命令 | 路径穿越防护；`/exmenu check` 校验器 |
| `MenuGuiManager` | 渲染 + 点击回调 | 批量查库消除 N+1；`param.copy()` 防串味 |
| `ConfirmGuiManager` | 二次确认界面 | 配置缺失时退化为直接执行 |
| `ButtonParser` | YAML → 模型 | 宽容解析，结构性错误才丢弃 |
| `MenuChecker` | 五关校验 | 顺序不可调；返回枚举而非 boolean |
| `MenuExecutor` | 动作链 + 商店 | 先校验后记录；递归支持下 `[delay]` |
| `ConditionEvaluator` | 条件求值 | 双字符优先；解析失败返回 null |
| `InputManager` | 输入会话（静态） | `ConcurrentHashMap` + 超时兜底 |
| `MenuItemService` | 物品库 DAO | MD5 去重 upsert |
| `MenuLimitService` | 限制 DAO | **原子 UPDATE**，消除读后写竞态 |
| `Storage` / `Sqlite` / `Mysql` | 存储后端 | 方言差异收敛在接口 |
| `SchedulerUtil` | 统一调度 | 类加载探测 Folia |
| `LangUtil` | 文本 | 内置默认值，缺失键返回 `<key>` |
| `TextUtil` | 文本处理 | 颜色（含 hex）、占位符、分段 |
| `ItemStackUtil` | 物品构建 | 跨版本材质、头颅、序列化 |

---

## 19. 相关文档

- [配置文档](CONFIGURATION.md) —— 全部配置项
- [指令文档](COMMANDS.md) —— 指令与权限
- [开发文档](DEVELOPMENT.md) —— **当前实现**的开发指南（构建、扩展指南）
- [文档索引](README.md) —— 从这里开始
