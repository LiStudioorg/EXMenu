# EXMenu 开发文档

> 面向**修改本插件源码**的开发者。
> 只想配置菜单请看 [配置文档](CONFIGURATION.md)，只想知道指令怎么用请看 [指令文档](COMMANDS.md)。
>
> 本文件描述的是 **`newgen/` 模块**，也就是当前唯一在维护的实现。
> 旧实现（`src/`，HandyLib 注解驱动）已于重写时删除，
> 其源码保留在 git 历史中（提交 `95d5ba0` 及更早）。

---

## 目录

1. [项目概览](#1-项目概览)
2. [构建环境](#2-构建环境)
3. [包结构与分层](#3-包结构与分层)
4. [启动与关闭](#4-启动与关闭)
5. [配置加载与重载](#5-配置加载与重载)
6. [菜单渲染流程](#6-菜单渲染流程)
7. [点击处理与动作链](#7-点击处理与动作链)
8. [条件系统](#8-条件系统)
9. [限制系统](#9-限制系统)
10. [输入系统](#10-输入系统)
11. [数据模型与持久化](#11-数据模型与持久化)
12. [线程模型与 Folia](#12-线程模型与-folia)
13. [扩展指南](#13-扩展指南)
14. [已知缺陷与死代码](#14-已知缺陷与死代码)
15. [附录 A：核心类速查](#附录-a核心类速查)
16. [附录 B：术语表](#附录-b术语表)

---

## 1. 项目概览

EXMenu 是一个 **YAML 驱动**的 Minecraft 箱子菜单插件。服主写 YAML 描述
菜单长什么样、点了之后执行什么，插件负责渲染与执行。

| 项目 | 值 |
|---|---|
| 插件名 | `EXMenu` |
| 主类 | `cn.listudio.exmenu.MenuPlugin` |
| 源码 | `newgen/src/main/java/cn/handyplus/menu/ng/`（34 个文件，约 7300 行） |
| 资源 | `newgen/src/main/resources/`（9 个 YAML） |
| 字节码 | Java 17 |
| 构建 | Maven（根 pom 为聚合 pom） |
| 产物 | `newgen/target/EXMenu-1.1.0.jar`（约 16.4 MB） |
| 目标平台 | Spigot 与 Folia |
| 指令 | `/exmenu`（无别名） |

### 与旧实现的关系

这不是旧插件的"修 bug 版本"，而是**重写**：

| | 旧实现（已删除） | 当前实现 |
|---|---|---|
| 入口 | HandyLib 注解驱动 | 原生 `JavaPlugin` + `plugin.yml` |
| 字节码 | Java 8 | **Java 17** |
| 菜单格式 | `menu:` 下按槽位编号的 map | `buttons:` **列表**，每项含 `slot` |
| 依赖 | 作者私有仓库（HandyLib） | **全部公开仓库，克隆即可构建** |
| GUI 框架 | HandyInventory 自研封装 | IFramework 0.12.1 |

**这是有意的破坏性变更**：旧菜单文件不能直接迁移，需要改写
`menu:` 结构为 `buttons:` 列表。

---

## 2. 构建环境

### 2.1 依赖矩阵

| 依赖 | 版本 | 作用域 | 说明 |
|---|---|---|---|
| `dev.folia:folia-api` | 1.21.4-R0.1 | provided | 编译期契约，运行期由服务端提供 |
| `com.github.stefvanschie.inventoryframework:IF` | 0.12.1 | compile | **被 shade 重定位** |
| `com.cryptomorin:XSeries` | 14.0.0 | compile | **被重定位**，跨版本材质解析 |
| `de.tr7zw:item-nbt-api` | 2.15.4 | compile | **被重定位**；⚠️ 当前无源码引用 |
| `com.zaxxer:HikariCP` | 4.0.3 | compile | 连接池，**不重定位** |
| `org.xerial:sqlite-jdbc` | 3.36.0.3 | compile | SQLite 驱动，**不重定位** |
| `mysql:mysql-connector-java` | 8.0.29 | compile | MySQL 驱动，**不重定位** |
| `com.google.code.gson:gson` | 2.8.9 | compile | 物品序列化，**不重定位** |
| `me.clip:placeholderapi` | 2.11.6 | provided | 软依赖，反射调用 |
| `com.github.MilkBowl:VaultAPI` | 1.7 | provided | 软依赖，反射调用 |
| `org.bstats:bstats-bukkit` | 3.0.2 | provided | ⚠️ 当前未初始化 |

依赖仓库：papermc / codemc / spigot / extendedclip / jitpack，**全部公开**。

> **为什么 JDBC 驱动与 HikariCP 不重定位**：`java.sql.Driver` 的注册依赖
> `META-INF/services` 里写死的全限定类名，重定位后 ServiceLoader 找不到驱动。
> 代价是若服务端已加载同名类可能冲突 —— 用独立连接池命名
> （`EXMenu-SQLite` / `EXMenu-MySQL`）降低混淆风险。

### 2.2 构建命令

```bash
# 仓库根目录（聚合构建，推荐）
mvn clean package

# 或只构建模块
cd newgen && mvn clean package
```

根 `pom.xml` 是聚合 pom（`packaging: pom`），唯一模块是 `newgen`，
**自身不含任何依赖**，只是把构建入口收敛到仓库根 ——
这样 CI 与贡献者都只需要记住一条命令。

### 2.3 资源过滤

`plugin.yml` 参与 Maven 资源过滤（`filtering: true`），因此可写 `${project.version}`；
其余资源**不参与过滤**，`&` 与 `%` 不会被 Maven 破坏。

> 这点很重要：菜单与语言文件里满是 `%player_name%` 和 `&a`，
> 若开启过滤会被 Maven 当作占位符处理。

### 2.4 shade 重定位规则

| 原始包 | 重定位后 |
|---|---|
| `com.github.stefvanschie.inventoryframework` | `cn.listudio.exmenu.lib.if` |
| `com.cryptomorin.xseries` | `cn.listudio.exmenu.lib.xseries` |
| `de.tr7zw.changeme.nbtapi` | `cn.listudio.exmenu.lib.nbt` |
| `de.tr7zw.annotations` | `cn.listudio.exmenu.lib.nbt.annotations` |

同时排除 `META-INF/*.SF`、`*.DSA`、`*.RSA`、`MANIFEST.MF`、`module-info.class`。
（签名文件会让重定位后的 jar 校验失败。）

**重定位的后果**：源码里 import 的是**原始包名**，编译期正常，
但**运行期**这些类在 `cn.listudio.exmenu.lib.*` 下。
新增代码若用反射按字符串查找这些类，**必须写重定位后的名字**。

### 2.5 CI

`.github/workflows/build.yml`：JDK 21 构建（产出 Java 17 字节码），
用 `mvn -f newgen/pom.xml help:evaluate` 取版本号定位产物，
上传前校验 jar 存在，WebDAV 发布仅在 `push` 时执行。

> 旧 CI 需要 `MAVEN_SETTINGS_BASE64` 解码私有仓库凭据，**已移除** ——
> 现在任何贡献者克隆后都能直接构建。

---

## 3. 包结构与分层

```
cn.listudio.exmenu
├── MenuPlugin.java              插件入口，负责装配与生命周期
│
├── command/      presentation  指令解析与分发
├── listener/     presentation  事件监听（聊天输入、时钟物品、退出）
│
├── inventory/    render        菜单渲染、按钮解析、输入会话、确认界面
│   ├── MenuGuiManager          ★ 渲染核心
│   ├── ButtonParser            YAML → MenuButtonParam
│   ├── ConfirmGuiManager       二次确认界面
│   ├── InputManager            聊天输入状态机
│   ├── MenuSession             管理员预览标记
│   └── ConfigProvider          配置快照接口
│
├── core/         engine        业务规则
│   ├── MenuChecker             ★ 点击准入（五关）
│   ├── MenuExecutor            ★ 动作链执行
│   └── ConditionEvaluator      条件表达式求值
│
├── service/      persistence   业务级数据访问
│   ├── MenuItemService         物品库
│   └── MenuLimitService        次数与冷却
├── storage/      persistence   数据库后端抽象
│
├── param/  enter/  model       数据结构
├── config/                     配置快照
├── constants/                  枚举
└── util/                       横切工具
```

### 3.1 依赖方向

**严格单向**：

```
presentation  →  render  →  core  →  persistence  →  model
                     ↘         ↙
                        util
```

关键约束：

- `core` **不能** import `inventory` 的具体类。核心层通过
  `MenuExecutor.GuiContext`（只含 `close()` / `refresh()`）与界面交互，
  而不是持有 IFramework 的 `ChestGui`。
- `core` 通过 `MenuChecker.ConfigAccessor`（4 个 `isActionXxx()`）读配置开关，
  而不是依赖 `MenuConfig` 全量 API。
- `inventory` 通过 `ConfigProvider.get()` **每次现取**配置快照，不缓存引用 ——
  因为 `reload` 会就地替换快照内容，持旧引用会一直渲染旧菜单。

> **维护这条边界是刻意的**：它让 `core` 可以脱离 Bukkit 界面单独测试，
> 也让将来更换 GUI 框架时不必改动业务逻辑。

### 3.2 各包职责

| 包 | 文件数 | 职责 |
|---|---|---|
| `command` | 1 | 解析 `/exmenu`，权限校验，Tab 补全 |
| `listener` | 3 | 聊天输入拦截、时钟物品右键、玩家退出清理 |
| `inventory` | 7 | 渲染与界面交互 |
| `core` | 3 | 准入检查、动作执行、条件求值 |
| `service` | 2 | 物品库与限制的业务封装 |
| `storage` | 3 | 连接池与建表 |
| `param`/`enter` | 3 | `MenuButtonParam`、`MenuItem`、`MenuLimit` |
| `config` | 1 | 配置快照 `MenuConfig` |
| `constants` | 3 | 动作标签、输入类型、GUI 类型 |
| `util` | 7 | 文本、物品、调度、经济、PAPI、YAML、语言 |

---

## 4. 启动与关闭

`MenuPlugin.onEnable()` 按**固定顺序**执行六步，前两步失败即禁用插件：

| 步骤 | 动作 | 失败后果 |
|---|---|---|
| 1 | `MenuConfig.load(this)` | 记 SEVERE，`disablePlugin`，中止 |
| 2 | `initStorage()`：读 `storage.yml`，选后端，建池建表 | 记 SEVERE，`disablePlugin`，中止 |
| 3 | `ItemStackUtil.init()` / `EconomyUtil.init()` / `PlaceholderUtil.init()` | 各自降级（见 §4.2） |
| 4 | 构造 service、executor、guiManager | — |
| 5 | 注入回调（见 §4.1） | — |
| 6 | `registerCommand()` + `registerListeners()` | 见下 |

**顺序不可调换的原因**：步骤 4 的 `MenuGuiManager` 构造需要 `MenuConfig`
（拿 `noWorld`、`permission` 等开关），必须在步骤 1 之后；
`MenuLimitService` 需要已初始化的 `Storage`，必须在步骤 2 之后。

`registerCommand()` 取 `getCommand("exmenu")`，若为 null 只记 SEVERE
并**不中止** `onEnable` —— 指令不可用，但菜单与其它功能仍正常工作。

### 4.1 依赖注入

`newgen` **不使用任何 DI 框架**，装配全部手写在 `onEnable`：

```java
executor.setGuiOpener(guiManager);
executor.setSubActionHandler((player, menu, id, gui) ->
        executeSubAction(player, menu, id, gui));
InputManager.PluginHolder.set(this);
InputManager.ConfigHolder.set(this);
InputManager.GuiHolder.set(guiManager);
```

`InputManager` 的三组静态 holder 是**历史遗留** —— 它的静态方法无法访问实例，
只能用静态 holder 反查。**这是已知的设计瑕疵，新增功能不要照抄这种模式**；
需要实例时应改为把依赖作为参数传入。

### 4.2 降级行为

| 组件 | 缺失时的行为 |
|---|---|
| PlaceholderAPI | `enabled = false`，`set()` 原样返回输入 |
| Vault | `economy = null`，`isReady()` 为 false，涉及金钱的动作报"余额不足" |
| `exmenu` 指令声明 | 记 SEVERE，指令不可用，其余功能正常 |

> 降级是刻意的：菜单插件不该因为没装经济插件就无法启动。

### 4.3 关闭流程

`onDisable()` 按顺序做两件事：

1. `SchedulerUtil.cancelTasks(this)` —— **先取消挂起的任务**
2. `storage.close()` —— 再关闭连接池

**顺序不能反**：如果有 `[delay]` 动作在飞行中、或输入超时任务尚未触发，
它们会在连接池关闭后执行，拿不到连接而抛异常；更糟的是插件已卸载却仍在改玩家背包。

> Spigot 会在插件卸载时自动注销监听器，但**延迟任务不会自动取消**。
> Folia 下 `cancelTasks` 是空操作（该平台无批量取消入口），
> 任务随 region/实体生命周期回收。

**它仍然没有**：清理 `InputManager` 的会话表（由 `PlayerQuitListener` 按玩家清理）。

---

## 5. 配置加载与重载

### 5.1 配置快照

`MenuConfig` 是配置快照对象，持有：

| 字段 | 内容 |
|---|---|
| `menuConfigMap` | 菜单名 → `YamlConfiguration` |
| `commandMap` | 动态指令 → 菜单名（⚠️ 无消费者，见 §14.1） |
| `itemMap` | 物品 → 菜单名（⚠️ 无消费者） |
| `permissionMap` | 菜单名 → 所需权限 |
| `itemGuiConfig` / `confirmGuiConfig` | 内置界面配置 |

### 5.2 reload 的就地刷新

**这是最容易踩坑的地方。**

`MenuConfig.reload(plugin)` **不是**替换对象，而是：

1. 调 `load(plugin)` 构造一份**新的** `MenuConfig`
2. 把新对象的 24 个字段逐个复制到当前对象
3. **最后**才赋值那 6 个集合字段

**为什么这么绕**：调用方（`MenuGuiManager`、`MainCommand` 等）持有的是
`ConfigProvider` 引用。若 `reload` 换成新对象，这些引用仍指向旧快照。
就地复制保证所有持有者立刻看到新值，无需重新注入。

**为什么字段赋值放最后**：让 `menuConfigMap` 等集合**要么全是新的、要么全是旧的**，
避免渲染线程读到"新 title + 旧 buttons"的半成品状态。

### 5.3 按钮缓存必须手动丢弃

`MenuGuiManager` 用 `Map<String, List<MenuButtonParam>>` 缓存解析结果，
键是菜单文件名（**含 `.yml`**）。

**`MenuConfig.reload()` 不会清这个缓存** —— 它属于渲染层，配置层不应反向依赖。
所以 `/exmenu reload` 必须显式清理：

```java
MenuConfig config = configProvider.get();
config.reload(plugin);
if (guiManager != null) {
    guiManager.invalidateCache();   // 漏掉这行 → 玩家一直看到旧按钮
}
```

> 这是曾经**真实存在的缺陷**：`reloadPluginConfig()` 是唯一会清缓存的方法，
> 但它是死代码，`/exmenu reload` 直接调 `reload()` 绕过了它。
> 现在 `handleReload` 已改为显式调用 `invalidateCache()`。

### 5.4 storage.yml 不可热重载

连接池一旦建立，切换方言会让已缓存的 `PreparedStatement` 失效。
因此改 `storage.yml` **必须重启服务器**，`/exmenu reload` 对它无效。

---

## 6. 菜单渲染流程

核心：`MenuGuiManager.open(Player, String)`。

### 6.1 顺序

| 步骤 | 动作 | 失败行为 |
|---|---|---|
| 1 | `config.getMenu(name)` | 无此菜单 → `noMenu` |
| 2 | 补 `.yml` 后缀 | — |
| 3 | 权限校验 | 拒绝 → `noPermission` |
| 4 | 世界黑名单校验 | 拒绝 → `noCommandPermission` |
| 5 | `normalizeSize()`：夹到 9–54，向上取 9 的倍数 | — |
| 6 | 标题：PAPI → 颜色代码 | — |
| 7 | 建 `ChestGui`，设全局点击取消 | — |
| 8 | 解析按钮 + **两次批量查询** | — |
| 9 | 逐按钮：权限 → 隐藏 → 建物品 → 放槽位 | 任一不通过则跳过 |
| 10 | 有 `pane` 则填充背景 | — |
| 11 | `gui.show(player)` | — |

管理员预览（`/exmenu view`、`adminOpen`）在第 3、4 步**绕过**权限与世界校验。

### 6.2 批量查询（避免 N+1）

第 8 步只发**两次** SQL，而不是每个按钮一次：

```java
itemService.findMapByIds(ids)                            // 一条 IN 查询
limitService.findMapByPlayerUuid(player.getUniqueId())   // 一条 = 查询
```

`findMapByPlayerUuid` 每个 `open()` 只调一次，结果按 `menuItemId` 建 map。
这是渲染路径上最重要的性能设计 —— 54 槽菜单若逐按钮查库就是 54 次往返。

> ⚠️ `IN` 列表长度**无上限**，而 SQLite 的变量上限是 999。
> 按钮 id 极多时需要分片查询。

### 6.3 槽位冲突

同一槽位被多个按钮占用时，**`priority` 大者胜**；`priority` 相同则
**YAML 中靠后者覆盖靠前者**（IFramework 同槽 `addItem` 是替换语义）。

跳过发生在槽位判定**之前**：权限不足 / 条件隐藏 / 材质解析失败的按钮
不占用槽位，因此不会"挡住"低优先级按钮。

### 6.4 隐藏判定

`shouldHide()` 依次判断，任一成立即隐藏：

1. `limitHide` 且 `limit > 0` 且已用次数 ≥ 上限
2. `cdHide` 且剩余冷却 > 0
3. `conditionsHide` 命中

> `limitHide` / `cdHide` **独立于** `action.limit` / `action.cd` 全局开关 ——
> 即使全局关闭了次数限制，`limitHide` 仍生效。
> 这是刻意的：**隐藏是显示层行为，开关是执行层行为**。

### 6.5 物品构建

`id > 0` → 查物品库 `getItemJson()` 反序列化，**失败则回退到 `material`**；
否则直接按 `material` 解析。

材质解析三步：`Material.matchMaterial()` → `XMaterial.matchXMaterial()` →
失败返回 null（记 WARNING 并跳过该按钮）。
委托 XSeries 是为了兼容 1.7–1.21 的历史材质名（如 `WOOL:14`）。

### 6.6 按钮解析的条件

`ButtonParser.parse()` 在 **`slot` / `slots` 全为空时返回 null**，
该按钮被直接丢弃。解析不透传异常 —— 单个按钮写错不会让整个菜单打不开。

---

## 7. 点击处理与动作链

### 7.1 点击回调

`MenuGuiManager.createGuiItem()` 中的顺序：

1. `event.setCancelled(true)`
2. **`param.copy()`** 后 `setEventClickType(event.getClick())`
3. 从**渲染时的快照**取 limit，不重新查库
4. 有 `input` → 走输入流程；否则 `MenuChecker.check()`
5. 不通过 → 播失败音 + 发提示，返回
6. `confirm: true` → 开确认界面
7. 否则 `executor.execute()`

> **第 2 步的 `copy()` 是强制的**：`MenuButtonParam` 实例被缓存并由所有玩家共享，
> 直接 `setEventClickType` 会让并发点击互相覆盖。
> `copy()` 深拷贝全部标量与集合（但 `raw` 仍是共享引用，且
> `eventClickType` **刻意不拷贝**）。

### 7.2 准入检查

`MenuChecker.check()` 五关，**顺序不可调换**：

| 顺序 | 检查 | 返回 |
|---|---|---|
| 1 | 次数限制 | `LIMIT_REACHED` |
| 2 | 冷却 | `COOLING_DOWN` |
| 3 | 点击类型 | `WRONG_CLICK_TYPE` |
| 4 | 自定义条件 | `CONDITION_FAILED` |
| 5 | — | `ALLOWED` |

**顺序按成本排序**：自定义条件可能触发十几次 PAPI 解析，放最后。
点击是高频操作，把贵检查放前面会明显拖慢无效点击的响应。

**用 `Result` 枚举而非 boolean**，是为了让调用方知道**为什么**被拒绝 ——
不同原因要播不同音效、发不同消息。旧版只返回 boolean，
导致所有失败都只能给一句笼统提示。

`check()` **不修改任何状态**。记录点击是 `MenuExecutor` 的职责 ——
因为商店校验可能失败，那时**不应留下使用记录**。

### 7.3 执行

`MenuExecutor.execute()`：

1. 商店前置校验（失败则播失败音并返回，**不记录次数**）
2. `applyLimits()` 记录使用
3. 播成功音
4. 执行 `commands`
5. 按点击类型执行 `actions.left` / `right` / `middle`
6. `close: true` 则关闭界面

> **第 1 步先于第 2 步是关键设计**：买不起的时候不应该消耗次数。

### 7.4 动作链

`runCommands(player, actions, gui, index)` 是**递归**的，逐个执行并 `index + 1`。
返回值 void —— 链的终止靠 `return`，不靠返回码。

终止条件：

| 情况 | 行为 |
|---|---|
| `index >= size` | 正常结束 |
| `player.isOnline()` 为 false | **静默放弃剩余动作** |
| 空行 | 记 `configError`，**终止整条链** |
| 标签无法识别 | 记 `configError`，终止整条链 |
| `[open]` | 打开菜单后**终止**（不再执行后续） |
| `[delay]` | 调度延迟任务，**终止当前调用栈**（由延迟任务续跑） |

> **空行会中断整条链**，这是配置时最容易踩的坑 ——
> 一个空行会让后面所有动作都不执行。

### 7.5 变量替换顺序

```
PAPI 替换  →  %input% 替换  →  %player% 替换
```

**顺序不能变**：玩家输入本身可能包含 `%`。若先替换 `%input%`，
输入里的 `%xxx%` 会被 PAPI 当成变量再解析一次。

### 7.6 标签匹配

`CommandTypeEnum.match()` 用**最长匹配**（不是首个匹配），
前缀比较**包含结尾的 `]`**。这样 `[op]` 不会抢走 `[opPerformCommand]`。

匹配是**大小写不敏感**的（`regionMatches(true, ...)`）。

> ⚠️ 后果：标签名不能是"另一个标签名 + `]`"，否则会被误判。
> 也正因包含 `]`，`[actionbar]` 这种**未定义**的标签不会被 `[action]` 匹配，
> 而是**完全匹配不上** → 报 `configError` → **中断整条动作链**。

---

## 8. 条件系统

`ConditionEvaluator.evaluate(String)` —— 纯函数，无状态，线程安全。

### 8.1 运算符识别

**两字符运算符必须先于单字符判断**：

```
>=  →  <=  →  !=  →  ==  →  =  →  >  →  <
```

否则 `>=` 会被误判为 `>`，右操作数变成 `=5`。

### 8.2 数值解析

`parseNumber()` 在以下情况返回 **null**（而不是 0）：

- 空字符串
- 以 `%` 开头或结尾（说明 PAPI 未替换成功）
- `NumberFormatException`

> **返回 null 而非 0 是刻意的**：把 `%player_level%` 解析失败当成 0，
> 会让"等级 ≥ 10"悄悄变成"0 ≥ 10"（结果恰好也是 false），
> 但"等级 == 0"就会**错误地成立**。
> null 传播后条件一律判为不满足，不会误判为通过。

### 8.3 比较规则

| 情况 | 支持 |
|---|---|
| 两侧都是数值 | `==` `!=` `>` `<` `>=` `<=` 全部支持 |
| 任一侧非数值 | **只支持 `==` 和 `!=`**（字符串比较），排序比较一律 false |

### 8.4 返回值语义

> ⚠️ **最容易搞混的一点**：`checkConditions()` 返回 **true 表示"条件不满足"**。

| 方法 | true 的含义 |
|---|---|
| `checkConditions()` | 条件**不满足**（应拦截） |
| `checkConditionsHide()` | **应当隐藏** |
| `shouldHide()` | **应当隐藏** |
| `handleShopBefore()` | **可以继续**（允许） |

前三个是"true = 拦截/隐藏"家族，其余是"true = OK"。

### 8.5 限制

- **不支持 `!` 取反**：前导 `!` 没有运算符路径，会被 `parseBoolean` 判为真
- **不支持 AND / OR**
- **不支持连续比较**：`a >= b >= c` 会拿 `a` 与字符串 `"b >= c"` 比较

> 条件右侧**留空**（如 `'%player_name% != '`）会被判为不满足，
> 结果是按钮**对所有人隐藏**。这是很难从配置上看出来的错误。

---

## 9. 限制系统

### 9.1 数据模型

`menu_limit` 表，唯一键 `(player_uuid, menu_item_id)`：

| 列 | 含义 |
|---|---|
| `number` | 已点击次数 |
| `click_time` | 上次点击时间戳（毫秒） |

只有 `id != 0` 的按钮才有记录 —— **`id` 是限购与冷却的前提**。

### 9.2 并发安全

写库用**数据库原子操作**，而不是"读-改-写"：

```sql
UPDATE menu_limit SET number = number + ?, player_name = ?
WHERE player_uuid = ? AND menu_item_id = ?
```

自增由数据库完成，不存在读取后覆盖的窗口。

插入路径用 `insertRecord(...) || forceAddNumber(...)`：
`insertRecord` **故意吞掉** `SQLException`（并发下唯一键冲突是预期的），
`||` 的短路语义保证冲突后一定再执行一次 `forceAddNumber`，所以计数不会丢。

`setClickTime` 采用同样的形状（幂等覆盖 + 强制更新兜底）。

### 9.3 原子闸门（曾经的竞态已修复）

**次数限制由数据库原子裁决**，不再"读后判断"：

`MenuLimitService.tryIncrementWithinLimit()` 执行
`UPDATE ... SET number = number + 1 WHERE ... AND number < ?`，
由 `executeUpdate()` 的返回行数决定是否放行。没有命中时说明要么已达上限、
要么记录不存在，此时尝试插入 `number = 1` 的新行；插入撞唯一索引即视为已达上限。

**为什么必须这样**：早期实现由 `MenuChecker` 拿渲染快照做
`used >= limit` 的 Java 判断，两个并发点击（乃至同一界面上连续点击）
都会读到同一个旧值而双双放行 —— 写库虽然是原子的，**判断不是**，
`limit: 5` 的按钮能被用 6 次以上。

三条执行路径统一走这个门槛：

| 路径 | 入口 |
|---|---|
| 直接点击 | `MenuGuiManager.createGuiItem` → 异步占位 → 回实体线程执行 |
| 输入完成后 | `InputManager.handle` → `MenuExecutor.executeWithLimitGate` |
| 确认界面点确定 | `ConfirmGuiManager` → `MenuExecutor.executeWithLimitGate` |

> ⚠️ 占位成功后会在 `MenuButtonParam.numberClaimed` 上置位，
> `applyLimits()` 见到该标记便**不再重复累加** —— 否则一次点击会被记成两次。

### 9.4 记录时机

`applyLimits()` 只做必要工作：

```java
needNumber = (limit > 0 && config.isActionLimit()) || param.isLimitHide();
needTime   = (cd > 0 && config.isActionCd())       || param.isCdHide();
```

两者都为 false 就直接返回，不碰数据库。

两个写操作在**同一个异步任务**里顺序执行，但**没有事务** ——
若中间被打断，时间戳与计数会不同步。

---

## 10. 输入系统

### 10.1 流程

按钮配了非空 `input` 时，点击后不执行动作，而是：

1. 建 `InputSession`（存**拷贝后**的按钮参数 + 来源界面）
2. `InputManager.begin()` 登记到 `ConcurrentHashMap`，登记超时任务
3. 关闭界面
4. 发提示语

玩家发言后由 `ChatInputListener` 在 **`EventPriority.LOWEST`** 拦截：

1. **先取消事件**（避免消息泄露到公共聊天）
2. `message == null` → 直接返回（已取消，会话保留）
3. 含 `%` → 拒绝并提示，**会话不结束**（玩家可重试）
4. 类型校验失败 → 提示，**会话不结束**
5. 通过 → `end()`，把输入值写入 `MenuButtonParam.inputValue`，`runAtEntity` 执行原动作

退出方式：输入大写 `T`（**精确匹配，区分大小写**）。

### 10.2 类型

`InputTypeEnum`：`STRING`（任意）、`NUMBER`（整数）、`POSITIVE_NUMBER`（正数）。

`parse()` 对**未知名称返回 STRING**（永不返回 null），属于宽松策略。
`validate()` 对**空输入一律返回 false**，三种类型都是。

### 10.3 `%` 的拒绝位置

> ⚠️ `%` 的拒绝**不在** `InputTypeEnum` 里，尽管那个类的注释这么写。
> 真正的校验在 `ChatInputListener.onChat()`。
> 这意味着若将来新增别的输入通道（如告示牌输入），**`%` 校验不会自动生效**。

### 10.4 输入值的传递方式

输入值**挂在 `MenuButtonParam.inputValue` 上**随参数一路下传，不用 `ThreadLocal`：

```java
// InputManager.handle —— 这里可能处于异步聊天线程
MenuExecutor.setInput(session.getParam(), message);
SchedulerUtil.runAtEntity(plugin, player, () -> executor.execute(...));
```

> ⚠️ **为什么不能用 `ThreadLocal`**（早期实现的做法，已修）：
> `handle()` 在**聊天事件线程**写值，`execute()` 在**实体线程**读值，
> 两个线程不同 → `getInput()` 永远返回空串，`%input%` 全部替换成空。
> 而且写入侧的线程局部值会在服务端池化线程上残留，
> 把上一个玩家的输入泄漏给下一个在此线程执行的玩家。
>
> `runCommands()` 的 `inputValue` 形参随之逐层下传，
> `[delay]` 续跑时也一并带上。

### 10.5 其它已知限制

- `begin()` 无条件 `put`，第二次点击会**静默覆盖**上一个会话
- 超时任务用**游戏刻**（`seconds * 20`），过期判断用**墙钟毫秒**，
  服务器卡顿时两者不一致
- `timeoutSeconds <= 0` 时**完全不登记超时任务**，会话永久挂起

---

## 11. 数据模型与持久化

### 11.1 表结构

```sql
-- 物品库
CREATE TABLE IF NOT EXISTS menu_item (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    menu_name   VARCHAR(64),
    item_md5    VARCHAR(64),
    item_json   TEXT,
    create_time BIGINT,
    UNIQUE (item_md5)
);

-- 次数与冷却
CREATE TABLE IF NOT EXISTS menu_limit (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    player_name  VARCHAR(64) NOT NULL,
    player_uuid  VARCHAR(64) NOT NULL,
    menu_item_id INT NOT NULL,
    number       INT NOT NULL,
    click_time   BIGINT,
    UNIQUE (player_uuid, menu_item_id)
);
```

除这两个唯一约束**没有任何二级索引**。`findMapByPlayerUuid` 靠复合唯一键的
最左列命中。

### 11.2 后端差异

| | SQLite | MySQL |
|---|---|---|
| 池大小 | **固定 1** | 默认 8 |
| 主键语法 | `INTEGER PRIMARY KEY AUTOINCREMENT` | `INT NOT NULL AUTO_INCREMENT` |
| 唯一语法 | `UNIQUE (...)` | `UNIQUE KEY 名 (...)` |
| 建表后缀 | — | `ENGINE=InnoDB CHARSET=utf8mb4` |
| 其它 | WAL + `busy_timeout=5000` | 显式 `maxLifetime` / `keepaliveTime` |
| `connectionTestQuery` | `SELECT 1` | 无 |

> **SQLite 池大小为什么是 1**：SQLite 的写锁是**库级**的，
> 多连接只会增加 `SQLITE_BUSY` 概率，并不提高并发。串行化反而是最优解。
> 配合 WAL 模式，读操作仍可并发。

### 11.3 服务层约定

`MenuItemService` 与 `MenuLimitService` 的方法**全部是同步阻塞的**，
必须在异步线程调用。它们**不抛异常**，失败时返回
null / false / -1 / 空集合，并通过构造时传入的 `Logger` 记录。

> 这种"吞异常 + 返回值表达失败"的风格是有意的：点击路径不该因为
> 一次数据库抖动就把异常抛到事件总线。

### 11.4 `item_json` 的真实格式

`MenuItem.itemJson` 的注释说是"base64 编码的 Bukkit 序列化结果"，**这是过时的**。
实际是 `ItemStack.serialize()` 的 **Gson 明文 JSON**：

```java
GSON.toJson(item.serialize())
```

反序列化用 `ItemStack.deserialize(map)`。
用明文 JSON 便于排查 —— 早期用 NBT 时，出问题只能看到一堆二进制。

### 11.5 物品去重

物品按 `item_md5`（序列化 JSON 的 MD5）去重，唯一键在数据库层保证。
`/exmenu item` 存入前先 `findIdByMd5`，命中则返回已有 id。

---

## 12. 线程模型与 Folia

### 12.1 Folia 检测

`SchedulerUtil` 在**类加载时**检测一次：

```java
Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
```

用 `Class.forName` 而非读版本号，是因为它检测的是**线程模型能力**，
而不是某个具体版本 —— Folia 的 API 存在与否才是关键。

### 12.2 调度对照

| 需求 | Spigot | Folia |
|---|---|---|
| 主线程任务 | `Bukkit.getScheduler().runTask` | `getGlobalRegionScheduler().run` |
| 延迟任务 | `runTaskLater` | `runDelayed` |
| 异步任务 | `runTaskAsynchronously` | `getAsyncScheduler().runNow` |
| 实体相关 | `runTask` | `entity.getScheduler().run` |
| 取消任务 | `cancelTasks(plugin)` | **空实现**（Folia 无等价 API） |

> **绝不要直接写 `Bukkit.getScheduler()`** —— 在 Folia 上会抛异常。
> 一律经由 `SchedulerUtil`。

`runAtEntity()` 传 **null 实体时会回退到 `runTask`**。

### 12.3 硬性规则

| 操作 | 允许的线程 |
|---|---|
| 数据库访问 | **异步**（或 Folia 的 async scheduler） |
| 玩家 / 背包 / 实体操作 | **实体所属线程** |
| 打开 / 关闭界面 | 实体线程 |
| 一切调度器调用 | 经由 `SchedulerUtil` |

### 12.4 参数单位不一致

> ⚠️ `runTaskLater(plugin, task, delay)` 的 `delay` 是**游戏刻**，
> 而 `runTaskLaterAsynchronously(plugin, task, delay)` 的 `delay` 是**毫秒**
> （内部换算 `delay / 50`）。两个方法单位不同，极易写错。

另外 `runTaskLater` 内部做了 `Math.max(1L, delay)` 钳制
（Folia 要求延迟必须为正）。

---

## 13. 扩展指南

### 13.1 新增一个子指令

需要改 `command/MainCommand.java` 的 **4 处**：

**① 注册权限**（`PERMISSIONS`，第 53–66 行）

```java
PERMISSIONS.put("myCommand", "exmenu.myCommand");
```

> 这里的键用 **camelCase**（如 `addItem`）。

**② 加 case 分支**（第 118–145 行）

```java
case "mycommand":            // ← 注意：这里必须全小写
    return handleMyCommand(sender, args);
```

> ⚠️ **这是最容易出错的地方**：`PERMISSIONS` 的键是 camelCase，
> 而 `case` 标签是小写。因为 `onCommand` 先做
> `sub = args[0].toLowerCase(Locale.ROOT)` 再查 `PERMISSIONS`，
> 所以两者大小写形式不同但指向同一项。**两处都要写对**。

**③ 写处理方法**，返回 `boolean`（`true` 表示已处理，Spigot 要求）

**④ Tab 补全**（`onTabComplete`，第 935 行起）

- `args.length == 1`：自动遍历 `PERMISSIONS.keySet()`，**无需改动**
- `args.length == 2`：如需菜单名补全，加进第 952 行的菜单名单
- `args.length == 3`：目前返回 `null`（不给建议）

> ⚠️ **大小写陷阱（已修复，但新加时仍要注意）**：
> `sub` 是 `args[0].toLowerCase()` 的结果，所以**必须用小写字面量比较**。
> 这两处曾写成 `"adminOpen"` / `"addItem"` / `"getItem"`，
> 与已小写的值**永远不相等** → `/exmenu adminOpen <TAB>` 不给任何菜单名建议。
> 已改为 `"adminopen"` / `"additem"` / `"getitem"`。

> 另外注意：`onCommand` 对**未知子指令**返回的是 `noCommandPermission`
> 而不是"未知指令"。新增分支时不必处理这个 —— 但要知道现象。
>
> **别忘了 `plugin.yml` 的 `permissions` 段**，否则权限节点不会出现在
> 权限管理插件的列表里（功能上不报错，但管理员看不到）。

### 13.2 新增一个动作标签

需要改 `constants/CommandTypeEnum.java` 的 **2 处** + `core/MenuExecutor.java` 的 **1 处**：

**① 加枚举常量**

```java
ACTIONBAR("actionbar", "发送动作栏消息"),
```

**② 无需改 `match()`** —— 它遍历 `values()` 自动生效

**③ 在 `runCommands` 的 switch 里加 case**

```java
case ACTIONBAR:
    sendActionBar(player, body);
    break;
```

> ⚠️ **忘了第 ③ 步的后果**：标签能匹配上，但落到 `default` 分支，
> 报 `configError` 并**中断整条动作链**。
> 这正是 `[opPerformCommand]` 曾经的真实缺陷。

**匹配算法的陷阱**：`match()` 用**最长匹配**且比较**包含 `]`**，
所以 `[op]` 不会抢走 `[opPerformCommand]`。反过来说，
新标签名不能与已有标签构成"前缀 + `]`"关系。

**`knownTags()` 需要同步更新吗？** 它是硬编码列表，但
**没有任何调用点**（死代码）。可以不管，但更好的做法是**删掉它**，
免得长期与 `CommandTypeEnum` 不一致。

### 13.3 新增一个按钮属性

需要改 **3 个文件**，缺一不可：

**① `inventory/ButtonParser.parse()`** —— 从 YAML 读出来

```java
param.setMyField(section.getString("myField", "默认值"));
```

**② `param/MenuButtonParam.java`** —— 加字段 + getter/setter

> ⚠️ **别忘了 `copy()`**（第 651 行附近）！该方法**手工逐个复制**所有字段。
> 漏掉的字段在**点击时**会丢失值（点击路径必走 `copy()`），
> 表现为"配置读到了但点下去没效果"，极难排查。

**③ 真正消费它** —— 在 `MenuGuiManager`（渲染）或 `MenuExecutor`（动作）

> ⚠️ **只做 ①② 不做 ③ 是完全无效的**。当前已有多个字段处于这种状态，见 §14.2。

### 13.4 新增一个存储后端

实现 `storage/Storage.java` 的 7 个方法：

| 方法 | 有无默认实现 |
|---|---|
| `init(Plugin, YamlConfiguration)` | 无 |
| `getConnection()` | 无 |
| `close()` | 无 |
| `dialect()` | 无 |
| `autoIncrementPrimaryKey()` | 无 |
| `createTableIfNotExists(String, String)` | **有**（通用 `IF NOT EXISTS`） |
| `dataDirectory()` | **有**（返回 null） |

然后在 `MenuPlugin.initStorage()` 加分支：

```java
storage = "mysql".equals(type) ? new MysqlStorage() : new SqliteStorage();
```

**契约**：

- 所有方法都可能被异步线程调用，**实现必须线程安全**
- `getConnection()` 返回的连接由**调用方**关闭（服务层用 try-with-resources）
- 建表语句在 `init()` 内执行，**重复调用应当幂等**

> ⚠️ 当前两个实现的 `init()` 都无条件新建 `HikariDataSource`，
> **并不幂等** —— 重复调用会泄漏前一个池。新增后端时建议顺手修掉。

### 13.5 新增一个界面

参考 `ConfirmGuiManager`（178 行，最简单的完整例子）。

IFramework 的最小模式：

```java
ChestGui gui = new ChestGui(rows, title);
gui.setOnGlobalClick(event -> event.setCancelled(true));   // 必备
StaticPane pane = new StaticPane(9, rows);

GuiItem item = new GuiItem(itemStack, event -> {
    event.setCancelled(true);
    // 处理点击
});
pane.addItem(item, Slot.fromIndex(slot));    // 同槽重复添加 = 替换

gui.addPane(Slot.fromIndex(0), pane);
gui.show(player);
```

**要点**：

- `setOnGlobalClick` 取消是**必须的**，否则玩家能把菜单里的物品拖进背包
- `Slot.fromIndex(slot)` 的 slot 从 0 开始
- 关闭界面用 `gui.close()` —— 但必须从**实体线程**调用
- 需要异步取数时：先异步查库，再 `SchedulerUtil.runAtEntity` 回实体线程构建界面
  （**IFramework 不是线程安全的**）

**`constants/GuiTypeEnum` 有用吗？** **没有。整个类是死代码**，
无任何引用。新增界面不需要碰它。

### 13.6 接入新经济插件 / 使用动作钩子

**经济**：`util/EconomyUtil` 目前只支持 Vault，全部通过**反射**调用
（`getBalance` / `withdraw` / `deposit` / `format`）。
用反射是因为 Vault 是 `provided` 作用域 —— 运行期可能不存在，
直接 import 会导致 `NoClassDefFoundError`。

接新经济插件建议照此模式：`init()` 里探测并缓存 `Method`，
失败则 `isReady()` 返回 false，让上层平滑降级。

**五个钩子接口**：

| 接口 | 位置 | 方法 |
|---|---|---|
| `GuiContext` | `MenuExecutor` 内部 | `void close()` / `void refresh()` |
| `GuiOpener` | `MenuExecutor` 内部 | `boolean open(Player, String)` |
| `SubActionHandler` | `MenuExecutor` 内部 | `void run(Player, String, int, GuiContext)` |
| `ConfigAccessor` | `MenuChecker` 内部 | `isActionCd()` / `isActionLimit()` / `isActionMoney()` / `isActionPoint()` |
| `ConfigProvider` | `inventory` 包 | `MenuConfig get()` |

实现者：`MenuGuiManager` 实现 `GuiOpener` 与 `ConfigProvider`；
`MenuConfig` 实现 `ConfigAccessor`；
`GuiContext` 由 `MenuGuiManager.wrapFor(...)` 返回匿名实现。

> ⚠️ 构造 `GuiContext` 时**必须传 plugin**。
> `wrap()` 有个静态重载会传 null plugin，导致
> `Bukkit.getScheduler().runTask(null, ...)` 抛 NPE。
> 新代码请用实例方法 `wrapFor(...)`。

---

## 14. 已知缺陷与死代码

本节内容与 [架构文档 §15](ARCHITECTURE.md#15-已知缺陷与未完成项) 一致。

### 14.1 未完成的功能

| 功能 | 现状 |
|---|---|
| `[point]` 点券 | **占位实现**，只发一句提示音 |
| `[performCommand]` | 与 `[player]` 完全相同，无权限绕过机制 |
| `openCommand` / `openItem` | 配置已解析进 `commandMap` / `itemMap`，但**无监听器消费** |
| Shift+F | `config.yml` 有配置，**无消费者** |
| `gui/item.yml` | 已加载，**无消费者**（物品库界面未接线） |
| `GuiTypeEnum` | 整个类死代码 |
| `shopType: buy` / `sell` | 只支持 `leftBuyRightSell` / `leftSellRightBuy` |
| 更新检查 | `isCheckUpdate` 无实现 |
| `EconomyUtil` | 只支持 Vault |
| 图形化创建 / 编辑菜单 | 未实现，`/exmenu create` 只写骨架文件 |

### 14.2 无渲染效果的按钮字段

已解析进 `MenuButtonParam` 但**没有任何消费点**：

- `hideEnchant`（`hideFlag` 会传递，但这个字段本身无效）
- `head` / `headBase` —— `ItemStackUtil.applyHeadTexture()` 公开但**无调用者**
- `openItem`
- `dynamic`（派生字段，无消费者）

以及两个从未被调用的方法：`hasLimit()`、`isPersistent()`。

### 14.3 其它死代码

- `MenuChecker.LimitView` —— 内部接口，**零实现、零引用**
- `YamlUtil` 的 `getStringList` / `getSection` / `getString` / `getInt` /
  `getBoolean` / `loadDirectory` —— **全部 6 个都无调用**
  （代码里改用 `ButtonParser.toStringList` 和裸 getter）
- `MenuItemService.findAll` / `count` / `deleteByIds` / `emptyMap`
  （`findAll` 与 `count` 是物品库界面**本来需要的** API）
- `MenuLimitService.findAll`
- `SqliteStorage.warn`、`InputManager.size`、`InputSession.elapsedSeconds`
- `MenuExecutor.needsInput` / `recordUsage` / `knownTags`
- `MenuGuiManager.openAsPlayer` / `getSize` / `refresh(Player)`
- `MenuButtonParam.getRaw` / `hasLimit` / `isPersistent`
- `SchedulerUtil.isFolia` / `cancelTasks` / `runTaskLaterAsynchronously`
- `PlaceholderUtil.isEnabled`、`ConfirmGuiManager.sendHint`、
  `ClockInteractListener.cleanup`、`ItemStackUtil.applyHeadTexture`
- `MenuConfig` 的 `getLanguage` / `isAutoCreateId` / `getAdminOpenPermission` /
  `isCheckUpdate` / `isShiftEnable` / `getShiftMenu` / `getCommandMap` /
  `getItemMap` / `getItemGuiConfig`
- `pom.xml` 声明的 `item-nbt-api` 与 `bstats` —— **无源码引用**
  （`Metrics` 在零个源文件中出现）
- 语言文件里**无调用**的键：`noPlayerFailureMsg`、`money`、`point`、`addMenuItemMsg`
  （三个语言文件与 `LangUtil.DEFAULTS` 都有，但无人读取）

### 14.3.1 会误导人的注释

写新代码前值得知道的几处**注释与实现不符**：

| 位置 | 注释声称 | 实际 |
|---|---|---|
| `CommandTypeEnum` 类注释 | 最长匹配是为了防止 `[op]` 抢走 `[opPerformCommand]` | 比较**含结尾 `]`**，`[op]` 本就不是 `[opPerformCommand]` 的前缀，该冲突不可能发生。真正的规则是：**仅同名不同大小写会冲突**（短的、先声明的胜） |
| `MenuItem.itemJson` | "base64 编码的 Bukkit 序列化结果" | 是 `ItemStack.serialize()` 的 **Gson 明文 JSON** |
| `InputTypeEnum` 类注释 | "含 `%` 的输入一律拒绝" | 该类**没有任何 `%` 检查**，拒绝逻辑在 `ChatInputListener` |
| `ConfigProvider.get()` | "永不返回 null" | `onEnable` 步骤 1 之前该字段就是 null |
| `storage/Storage.init()` | "幂等" | 两个实现都**无条件新建连接池**，重复调用会泄漏 |
| `MenuItemService.save()` | 原子 upsert | 实为 `findIdByMd5` → INSERT/UPDATE，并发冲突返回 null |
| `pom.xml` 的 NBT 注释 | 用于"物品库序列化" | 物品序列化用的是 Gson，`de.tr7zw` 零引用 |
| `GuiTypeEnum` 类注释 | 引用了 `HandyInventory` | 该类**在本代码库中不存在**（旧实现残留） |

### 14.4 值得优先修的

按影响排序：

1. **`Storage.init()` 不幂等**（§13.4）—— 重复调用会泄漏前一个连接池。
   当前只被 `onEnable` 调用一次，属潜在问题。

> 以下问题已在最近一轮修复中解决，见 §14.5：
> `MenuChecker` 闸门竞态（次数超发）、`InputManager` ThreadLocal 跨线程
> （`%input%` 恒为空）、`onDisable` 不取消延迟任务、`MenuItemService.save()`
> 非原子、确认界面绕过限购、`pane` 装饰板不生效、卖出不看存款结果。

### 14.5 已修复的缺陷（记录备查）

这些是**确认并修掉**的问题。记录下来是因为**同类错误很容易再犯**：

| 缺陷 | 影响 |
|---|---|
| `action.*` 用 `getBoolean()` 读，而配置写的是 `ALL` | **开箱即用时冷却、次数限制、经济动作全部被静默禁用** |
| `[opPerformCommand]` 缺 `case` 分支 | 落到 `default` → 报错并**中断整条动作链** |
| `/exmenu reload` 不丢弃按钮缓存 | 重载后玩家仍看到旧按钮 |
| `GuiContext` 构造时传入 null plugin | `runTask(null, …)` 抛 NPE |
| `onTabComplete` 用 camelCase 比较已小写的值 | `adminOpen` / `addItem` / `getItem` 的菜单名补全**永远不触发** |
| 示例菜单使用不存在的 `[actionbar]` 标签 | 中断该按钮的动作链 |
| 示例菜单条件右侧留空（`'%player_name% != '`） | 条件恒不满足 → **按钮对所有人隐藏** |
| 限购闸门"读后判断" | 连点可超过 `limit`；改为 `UPDATE ... WHERE number < ?` 原子占位 |
| `%input%` 用 `ThreadLocal` 跨线程 | 聊天线程写、实体线程读 → **`%input%` 恒为空**；改为挂在按钮参数上传递 |
| 确认界面执行时传 null 限购记录 | `confirm: true` 的按钮**可无限点击** |
| `pane` 配置节被当字符串读 | 菜单级装饰板**从未生效** |
| 卖出不看 `deposit()` 返回值 | 物品已扣、钱没到账，却提示"出售成功" |
| `onDisable` 不取消调度任务 | 卸载后 `[delay]` 续跑，连接池已关闭 → 异常 |
| `MenuItemService.save()` 先查后插 | 并发插入时败者误报"存储失败" |

> 多数属于"配置/代码语义错位""漏分支"或"跨线程假设不成立"。
> 新增功能时对照此表自检。

### 14.6 尚未验证的部分

**本插件从未在真实 Minecraft 服务端加载过。**

已完成的验证：编译、全部 53 个类字节码链接、9 个 YAML 结构校验
（标签合法性、条件两侧操作数、槽位范围、id 唯一性）、重定位零泄漏、全新克隆可构建。

**未验证**：运行期行为、IFramework 同槽 `addItem` 的真实语义
（优先级规则与 `ConfirmGuiManager.fillEmpty` 的注释都**假设**"后添加覆盖先添加"，
但未反编译确认）、`Material.isItem()` 在 1.13 上是否真能区分
`CUSTOM_MODEL_SUPPORTED`、XSeries 14.0.0 是否覆盖资源中使用的全部历史材质名。

---

## 附录 A：核心类速查

| 类 | 行数 | 职责 |
|---|---|---|
| `command/MainCommand` | 855 | 指令解析、权限、Tab 补全 |
| `core/MenuExecutor` | 720 | 动作链执行、限制记录 |
| `inventory/MenuGuiManager` | 691 | 渲染、按钮缓存、点击回调 |
| `param/MenuButtonParam` | 522 | 按钮全部属性（50 个字段） |
| `util/ItemStackUtil` | 357 | 材质解析、物品构建与序列化 |
| `service/MenuLimitService` | 348 | 次数与冷却的原子读写 |
| `config/MenuConfig` | 338 | 配置快照与就地重载 |
| `util/TextUtil` | 300 | 颜色、表达式求值、切分 |
| `service/MenuItemService` | 273 | 物品库 CRUD |
| `inventory/InputManager` | 245 | 输入会话状态机 |
| `core/MenuChecker` | 206 | 五关准入检查 |
| `MenuPlugin` | 201 | 装配与生命周期 |
| `inventory/ButtonParser` | 201 | YAML → param |
| `inventory/ConfirmGuiManager` | 178 | 二次确认界面 |
| `util/YamlUtil` | 165 | YAML 读写 |
| `util/LangUtil` | 157 | 语言与消息 |
| `core/ConditionEvaluator` | 156 | 条件表达式求值 |
| `util/EconomyUtil` | 155 | Vault 反射封装 |
| `listener/ClockInteractListener` | 137 | 时钟物品右键 |
| `util/SchedulerUtil` | 118 | Spigot / Folia 调度抽象 |
| `storage/SqliteStorage` | 113 | SQLite 后端 |
| `storage/MysqlStorage` | 101 | MySQL 后端 |
| `constants/CommandTypeEnum` | 96 | 16 个动作标签 |
| `enter/MenuLimit` | 91 | 限制记录实体 |
| `util/PlaceholderUtil` | 81 | PAPI 反射封装 |
| `enter/MenuItem` | 75 | 物品库实体 |
| `inventory/MenuSession` | 75 | 管理员预览标记 |
| `constants/InputTypeEnum` | 66 | 3 种输入类型 |
| `storage/Storage` | 61 | 存储接口 |
| `inventory/InputSession` | 57 | 输入会话 |
| `listener/ChatInputListener` | 56 | 聊天输入拦截 |
| `constants/GuiTypeEnum` | 38 | ⚠️ 死代码 |
| `listener/PlayerQuitListener` | 38 | 退出清理 |
| `inventory/ConfigProvider` | 12 | 配置快照接口 |

## 附录 B：术语表

| 术语 | 含义 |
|---|---|
| **菜单** | `menu/*.yml` 文件，对应一个箱子界面 |
| **按钮** | `buttons:` 列表中的一项 |
| **动作 / 标签** | `commands` 里以 `[xxx]` 开头的指令 |
| **物品库** | `menu_item` 表，按 MD5 去重的物品池 |
| **限制记录** | `menu_limit` 表中的一行，记某玩家在某按钮上的次数与时间 |
| **准入检查** | 点击后、执行前的五关校验 |
| **渲染快照** | `open()` 时一次性查出的 limit 与物品数据 |
| **管理员预览** | `/exmenu view` / `adminOpen` 时绕过权限与世界的标记，10 秒过期 |

---

**相关文档**：[架构文档](ARCHITECTURE.md) ｜ [配置文档](CONFIGURATION.md) ｜
[指令文档](COMMANDS.md) ｜ [架构文档](ARCHITECTURE.md) ｜ [文档索引](README.md)
