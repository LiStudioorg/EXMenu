# EXMenu 配置文档

> 面向**服主 / 配置者**。描述 **EXMenu**（`newgen` 模块）的全部配置项。
>
> 只想看指令怎么用？见 [指令文档](COMMANDS.md)。
> 想改源码？见 [开发文档](DEVELOPMENT.md)。

---

## 目录

1. [配置文件总览](#1-配置文件总览)
2. [config.yml](#2-configyml)
3. [storage.yml](#3-storageyml)
4. [菜单文件 menu/*.yml](#4-菜单文件-menuyml)
5. [按钮属性全表](#5-按钮属性全表)
6. [动作标签（commands）](#6-动作标签commands)
7. [条件表达式](#7-条件表达式)
8. [限制系统：冷却与限购](#8-限制系统冷却与限购)
9. [商店与交易](#9-商店与交易)
10. [输入系统](#10-输入系统)
11. [confirm.yml 与 item.yml](#11-confirmyml-与-itemyml)
12. [语言文件](#12-语言文件)
13. [常见配置陷阱](#13-常见配置陷阱)
14. [EXMenu 对外提供的 PAPI 变量](#14-exmenu-对外提供的-papi-变量)

---

## 1. 配置文件总览

首次启动后，插件数据目录（`plugins/EXMenu/`）下会出现：

```
config.yml                插件主配置
storage.yml               数据库配置
gui/confirm.yml           二次确认界面
gui/item.yml              物品库界面
languages/zh_CN.yml       语言文件
languages/zh_TW.yml
languages/en_US.yml
menu/example.yml          示例菜单
```

**数据目录名是 `EXMenu`**（不是旧版的 `PlayerMenu`），两类插件的配置**不通用**。

`menu/` 下每个 `.yml` 就是一个菜单，**文件名（含 `.yml`）即菜单名**。新增文件后执行
`/exmenu reload` 生效。

> ⚠️ **升级自旧版 PlayerMenu 的服主请注意**：菜单文件格式、`config.yml`、`storage.yml`
> 全部是**新的**，旧文件不能直接使用。详见 [第 13 节](#13-常见配置陷阱)。

---

## 2. config.yml

```yaml
# 语言文件 [中文简体:zh_CN;中文繁體:zh_TW;English:en_US]
language: zh_CN

# 禁止打开菜单的世界
noWorld:
  - "notWorld"

# 使用 /exmenu create 创建菜单时是否自动生成 id
autoCreateId: true

# 是否需要菜单打开权限。
# true 时：菜单文件里的 permission 节点生效，玩家需持有该权限才能打开
permission: false

# 聊天输入等待超时（秒），<= 0 表示不超时
inputTimeout: 30

# 时钟物品：进服赠送 / /exmenu clock 取回
clock:
  enable: false
  menu: example.yml
  item: CLOCK
  name: '&e菜单'
  slot: 8

# Shift+F 打开菜单（1.9+）
shift:
  F:
    enable: false
    menu: example.yml

# 限制生效的点击类型 ALL 全部 / LEFT 左键 / RIGHT 右键 / MIDDLE 中键
action:
  cd: ALL
  limit: ALL
  money: ALL
  point: ALL

# 是否检查更新
isCheckUpdate: false

# 首次启动时是否给管理员发送引导消息（只发一次）
firstRunHelp: true
```

### 2.1 通用项

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `language` | String | `zh_CN` | 语言文件，可选 `zh_CN` / `zh_TW` / `en_US` |
| `noWorld` | List | `[notWorld]` | **世界黑名单**：列表内世界无法打开任何菜单 |
| `autoCreateId` | Boolean | `true` | `/exmenu create` 生成的菜单是否自动写入 `id` |
| `permission` | Boolean | `false` | **是否启用菜单权限**。`true` 时菜单文件的 `permission` 节点才生效 |
| `inputTimeout` | Int | `30` | 聊天输入等待秒数；`<= 0` 表示不超时 |
| `isCheckUpdate` | Boolean | `false` | 是否检查更新 |
| `firstRunHelp` | Boolean | `true` | 首次启动时向第一个进服的管理员发送引导消息（只发一次） |

### 2.2 `clock` —— 时钟物品

进服时赠送一个时钟物品，右键可打开指定菜单。

| 键 | 默认 | 说明 |
|---|---|---|
| `clock.enable` | `false` | 是否开启 |
| `clock.menu` | `example.yml` | 时钟物品打开的菜单 |
| `clock.item` | `CLOCK` | 物品材质（Bukkit `Material` 名） |
| `clock.name` | `&e菜单` | 物品显示名 |
| `clock.slot` | `8` | 放置槽位；**该槽位已有物品时改为放进背包** |

> ⚠️ **一旦填写 `clock.menu`，`menu/` 下所有 `openItem` 配置将失效**，
> 所有通过物品触发的入口都统一打开这一个菜单。要让不同物品开不同菜单，
> 必须让 `clock.enable: false`。

`clock.enable: false` 时进服不赠送，`/exmenu clock` 也会提示"时钟物品功能未启用"。

### 2.3 `shift.F` —— Shift+F 打开菜单

| 键 | 默认 | 说明 |
|---|---|---|
| `shift.F.enable` | `false` | 是否开启 |
| `shift.F.menu` | `example.yml` | 要打开的菜单 |

> ⚠️ **开启后 Shift+F 会被占用**，玩家将无法再用该快捷键交换主副手物品。

### 2.4 `action` —— 限制的点击类型

`action` 下的四个键**不是布尔开关**，而是**点击类型选择器**：

| 取值 | 含义 |
|---|---|
| `ALL` | 全部点击类型都生效（默认） |
| `LEFT` | 仅左键 |
| `RIGHT` | 仅右键 |
| `MIDDLE` | 仅中键 |

| 键 | 作用 |
|---|---|
| `action.cd` | 冷却限制对哪些点击类型生效 |
| `action.limit` | 次数限制对哪些点击类型生效 |
| `action.money` | 金钱动作对哪些点击类型生效 |
| `action.point` | 点券动作对哪些点击类型生效 |

**兼容写法**：这四项也接受布尔值（`true` / `false` / `off` / `no` / `0` / `disable`）。
**关键规则：只有显式的 `false` 才表示关闭**，任何无法识别的值都按"开启"处理 ——
保守地保持功能可用，而不是因为解析失败把功能悄悄关掉。

### 2.5 热重载

`/exmenu reload` 会重载 `config.yml`、语言文件、`menu/` 下全部菜单、`gui/` 两个界面配置，
并**丢弃按钮缓存**。

> ⚠️ **`storage.yml` 不支持热重载**，修改后必须重启服务器。

---

## 3. storage.yml

```yaml
# 存储方法：SQLite 或 MySQL（请原样复制，不要自创写法）
type: sqlite

# SQLite 配置（type: sqlite 时生效）
sqlite:
  # 数据库文件名，位于插件数据目录下
  file: menu.db

# MySQL 配置（type: mysql 时生效）
mysql:
  host: 127.0.0.1
  port: 3306
  database: minecraft
  username: root
  password: ''
  # 连接参数不要随意删减：
  # useUnicode/characterEncoding 保证中文不乱码，serverTimezone 是 MySQL 8 驱动强制要求
  parameters: useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai
  pool-size: 8
  min-idle: 2
  connection-timeout: 10000
  max-lifetime: 1800000
  keepalive-time: 300000
```

| 键 | 说明 |
|---|---|
| `type` | `sqlite`（默认）或 `mysql`，**大小写不敏感** |
| `sqlite.file` | 数据库文件名，位于插件数据目录下 |
| `mysql.database` | 数据库名，**需提前手动创建** |
| `mysql.parameters` | 连接参数，**不要随意删减**：`characterEncoding` 保证中文不乱码，`serverTimezone` 是 MySQL 8 驱动强制要求 |
| `mysql.pool-size` | 连接池上限，默认 8 |

> ✅ **SQLite 的连接池固定为 1**，这是刻意的：SQLite 写锁是**库级**的，
> 多写连接只会互相阻塞并提高 `SQLITE_BUSY` 概率，串行化反而更快更稳。
> 同时开启 `journal_mode=WAL`（允许读写并发）与 `busy_timeout=5000`（遇锁等待而非立即失败）。

> ⚠️ **修改本文件后需要重启服务器**，`/exmenu reload` 不会切换存储后端。

---

## 4. 菜单文件 menu/*.yml

```yaml
# 界面标题
title: '&8菜单 - 示例'

# 界面大小，必须是 9 的倍数，范围 9~54
size: 27

# 绑定命令：玩家输入 /mc 即可打开本菜单
openCommand:
  - mc

# 绑定物品：手持该材质右键空气即可打开本菜单
# （若 config.yml 的 clock.menu 已填写，则此项失效）
openItem:
  - clock

# 打开本菜单所需权限。
# 仅当 config.yml 的 permission: true 时生效；
# 留空或不写表示无需权限。
permission: ''

# 打开时播放的音效，留空则不播放
sound: ''

buttons:
  - slot: 10
    material: ARROW
    name: '&e&l发送消息'
    commands:
      - '[message] &a你好, %player_name%!'

# 装饰板：填充边框，避免误点
pane:
  enable: true
  slots: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 17, 18, 19, 21, 23, 25, 26]
  material: BLACK_STAINED_GLASS_PANE
  name: ' '
```

### 4.1 菜单级键

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `title` | String | 文件名 | 界面标题，支持颜色代码与 PAPI |
| `size` | Int | `54` | 界面大小，**9~54 且为 9 的倍数**；不合法会自动规整 |
| `openCommand` | List | — | 绑定命令，玩家输入 `/mc` 打开。**可写多个** |
| `openItem` | List | — | 绑定物品材质（小写），手持右键空气打开。**可写多个** |
| `permission` | String | — | 打开本菜单所需权限；**留空表示无需权限** |
| `sound` | String | — | 打开菜单时播放的音效 |
| `buttons` | List | — | **按钮列表**，每项一个按钮 |
| `pane` | Section | — | 装饰板配置 |

> **`buttons` 是列表，不是按槽位编号的映射。** 每个按钮在自己的 `slot` 字段里
> 声明槽位 —— 这是与旧版 PlayerMenu 最主要的格式差异。

### 4.2 `openCommand` —— 绑定命令

```yaml
openCommand:
  - mc
  - menu
```

- **不用写 `/`**，只需命令名。
- 一个菜单可绑定**多个**命令。
- 命令名以小写比较，大小写不敏感。

### 4.3 `openItem` —— 绑定物品

```yaml
openItem:
  - clock
  - compass
```

- 用 Bukkit `Material` 枚举名，**小写**。
- 仅按**材质类型**判定，无法区分名称 / Lore / NBT。
- 同一材质只能对应一个菜单（后加载的覆盖先加载的）。
- 若 `config.yml` 的 `clock.menu` 已填写，**本项全部失效**。

### 4.4 `pane` —— 装饰板

```yaml
pane:
  enable: true
  slots: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 17, 18, 19, 21, 23, 25, 26]
  material: BLACK_STAINED_GLASS_PANE
  name: ' '
```

| 键 | 默认 | 说明 |
|---|---|---|
| `pane.enable` | `true` | 是否启用 |
| `pane.slots` | 空 = 铺满 | 要填充的槽位列表 |
| `pane.material` | — | 材质（必填，缺省则不铺） |
| `pane.name` | `' '` | 显示名 |

- `slots` **留空表示铺满所有未被按钮占用的槽位**；写了 `slots` 则只铺这些槽位。
- 装饰板在**所有按钮渲染完成后**填充，**已被按钮占用的槽位会被跳过**，
  不会覆盖按钮。
- 超出 `size` 范围的槽位会被忽略。

**旧式写法（仍兼容）**：直接把材质名写在 `pane` 上，等效于铺满全部空槽位：

```yaml
pane: BLACK_STAINED_GLASS_PANE
```

---

## 5. 按钮属性全表

`buttons:` 列表中的每一项支持以下键。**只有 `slot` / `slots` 是必需的** ——
没有槽位的按钮无法渲染，会被直接丢弃。

### 5.1 槽位（必需）

| 键 | 类型 | 说明 |
|---|---|---|
| `slot` | Int | 单个槽位，如 `slot: 13` |
| `slots` | List | 多个槽位，如 `slots: [10, 11, 12]` |

`slots` 优先于 `slot`。槽位从 **0** 开始，上限为 `size - 1`。

### 5.2 外观

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `material` | String | `STONE` | Bukkit `Material` 名，支持跨版本写法 |
| `name` | String | — | 显示名，支持颜色与 PAPI |
| `lore` | List | `[]` | 描述行 |
| `amount` | Int | `1` | 数量，**最小 1** |
| `dynamicName` | String | — | 动态名称，**每次渲染重新求值** |
| `dynamicLore` | List | `[]` | 动态描述 |
| `dynamicAmount` | String | — | 动态数量（支持 PAPI） |
| `enchant` | Boolean | `false` | 附魔光效 |
| `hideFlag` | Boolean | **`true`** | 隐藏属性（攻击力/耐久等） |
| `hideEnchant` | Boolean | **`true`** | 隐藏附魔描述 |
| `unbreakable` | Boolean | — | 不可破坏；**未配置则不设置** |
| `customModelData` | Int | `0` | 自定义模型数据 |
| `tooltipStyle` | String | — | 提示框样式（1.21.1+） |
| `head` | String | — | 头颅主人名，支持 PAPI（仅 `PLAYER_HEAD`） |
| `headBase` | String | — | 头颅材质 Base64 |

> ⚠️ **`hideFlag` / `hideEnchant` 默认为 `true`**（与多数布尔项相反）——
> 默认即隐藏，要显示须显式设为 `false`。

### 5.3 动作

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `commands` | List | `[]` | 动作链，**任何点击类型都执行** |
| `actions.left` | List | `[]` | 仅左键**额外追加**执行 |
| `actions.right` | List | `[]` | 仅右键额外追加 |
| `actions.middle` | List | `[]` | 仅中键额外追加 |
| `close` | Boolean | `false` | 执行后关闭界面 |
| `sound` | String | — | 成功音效 |
| `failSound` | String | — | 失败音效 |
| `priority` | Int | `0` | 同槽位覆盖优先级，**大者胜** |

左键点击时依次执行 `commands` → `actions.left`。

### 5.4 权限

| 键 | 类型 | 说明 |
|---|---|---|
| `permission` | List | 需**全部**满足才显示 |
| `notPermission` | List | 任一命中则隐藏 |

两者都可写成单个字符串（解析器会自动包装成列表）。

### 5.5 条件

| 键 | 类型 | 说明 |
|---|---|---|
| `conditions` | List | 需**全部**满足才可点击；不满足则拒绝并提示 |
| `conditionsHide` | List | 不满足则**隐藏按钮**（静默） |

### 5.6 限制

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `id` | Int | `0` | **限制身份标识**，见 [第 8 节](#8-限制系统冷却与限购) |
| `limit` | Int | `0` | 限购次数，`0` = 无限制 |
| `limitHide` | Boolean | `false` | 达上限后隐藏按钮 |
| `limitMsg` | String | — | 超限时的自定义提示 |
| `cd` | String/Int | `0` | 冷却，见下 |
| `cdHide` | Boolean | `false` | 冷却中隐藏按钮 |
| `cdMsg` | String | — | 冷却中的自定义提示 |

**`cd` 支持三种写法**：

```yaml
cd: 10      # 10 毫秒
cd: 5s      # 5 秒（也可写 5秒）
cd: 2m      # 2 分钟（也可写 2分）
cd: 1h      # 1 小时（也可写 1时）
```

> 💡 旧版只接受裸数字毫秒，写出 `5s` 会被解析成 **5 毫秒**，冷却形同虚设且极难排查。
> 现在推荐**总是带单位**。

### 5.7 商店

| 键 | 说明 |
|---|---|
| `shopType` | `leftBuyRightSell` 或 `leftSellRightBuy` |
| `shopMaterial` | 交易物品，格式 `材质:数量` |
| `shopPrice` | 价格（支持公式） |
| `shopNumber` | 数量（支持公式） |
| `shopGiveMaterial` | 交易后额外给予的物品，`材质:数量` |
| `shopEconomy` | 经济类型，默认 `money` |

详见 [第 9 节](#9-商店与交易)。

### 5.8 确认与输入

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `confirm` | Boolean | `false` | 先弹出确认界面 |
| `input` | String | — | 非空即触发输入流程 |
| `inputType` | String | 取 `input` 值 | `STRING` / `NUMBER` / `POSITIVE_NUMBER` |
| `inputMethod` | String | `CHAT` | 输入通道：`CHAT`（聊天栏）或 `ANVIL`（铁砧界面） |

详见 [第 10 节](#10-输入系统)。

### 5.9 槽位冲突

同一槽位被多个按钮占用时，**`priority` 大者胜出**。

跳过发生在槽位判定**之前**：权限不足、条件隐藏、材质解析失败的按钮**不占用槽位**，
因此不会"挡住"低优先级按钮。

---

## 6. 动作标签（commands）

`commands` 列表里每一项都必须以 `[标签]` 开头：

```yaml
commands:
  - '[message] &a你好, %player_name%!'
  - '[console] give %player_name% diamond 1'
```

**匹配是大小写不敏感的**，且采用**最长匹配**。

### 6.1 标签全表（22 个）

| 标签 | 说明 |
|---|---|
| `[message]` | 给点击的玩家发消息 |
| `[broadcast]` | 全服广播（支持 & 颜色码） |
| `[actionbar]` | 动作栏消息（跨 Spigot/Paper/Folia） |
| `[player]` | 以**玩家身份**执行命令（需要玩家有权限） |
| `[console]` | 以**控制台身份**执行命令（无需权限） |
| `[op]` | 以 **OP 身份**执行，执行后立刻收回 OP |
| `[opPerformCommand]` | 执行前置命令后以 OP 身份执行，格式 `前置命令;本体命令` |
| `[performCommand]` | 以**玩家身份**执行，**绕过权限检查** |
| `[dispatchCommand]` | 在**玩家所在位置**执行命令（命令方块形式） |
| `[close]` | 关闭当前菜单 |
| `[open]` | 打开另一个菜单 |
| `[title]` | 发送标题，格式 `主标题;副标题` |
| `[sound]` | 播放音效 |
| `[delay]` | 延迟后继续执行后续动作 |
| `[money]` | 给玩家加钱 |
| `[point]` | 给玩家加点券 |
| `[item]` | 给玩家物品，格式 `材质:数量` |
| `[effect]` | 给药水效果，格式 `效果名:秒数:等级`，逗号分隔多组（如 `SPEED:10:1,JUMP:5:1`） |
| `[heal]` | 回满生命并灭火（无需参数） |
| `[feed]` | 回满饱食度（无需参数） |
| `[exp]` | 给经验，默认给等级；`pts:100` 给经验值 |
| `[action]` | 自定义动作（执行另一个按钮） |

> ⚠️ **写了上表之外的标签**会报 `配置异常` 并**中断整条动作链**，
> 后面的动作全部不执行。改完菜单记得跑 `/exmenu check`。
> （历史上 `[actionbar]` 曾不在表内、触发过这一中断 —— 现已作为正式标签实现。）

### 6.2 执行身份对比

| 标签 | 执行身份 | 触发命令事件 | 需要权限 |
|---|---|---|---|
| `[player]` | 玩家 | ✅ | ✅ |
| `[performCommand]` | 玩家 | ❌ | ❌ |
| `[console]` | 控制台 | ✅ | — |
| `[dispatchCommand]` | 玩家位置 | ✅ | — |
| `[op]` | 临时 OP | ✅ | — |

> 💡 **推荐用 `[console]` 而非 `[op]`**：`[op]` 临时赋予 OP，在装了权限插件的
> 服务器上行为不可预期，且可能越权。

### 6.3 「执行命令」标签的区别

- `[player]` —— 就像玩家自己打了这条命令。**玩家没有权限就会失败。**
- `[performCommand]` —— 直接执行，**不触发命令事件**，因此绕过权限检查与其他插件的拦截。
- `[dispatchCommand]` —— 以玩家位置为执行点，命令方块语义。
- `[console]` —— 控制台身份，**权限最完整**，适合发物品 / 给权限。

### 6.4 终止条件

| 情况 | 行为 |
|---|---|
| 空行 | **报错并终止整条链** |
| 无法识别的标签 | **报错并终止整条链** |
| 玩家中途退出 | 静默终止 |
| `[delay]` | 挂到调度器后继续后续动作 |
| `[open]` | 打开新菜单后终止 |

> ⚠️ **`commands` 里绝对不能有空行** —— 一个空行会让**后面所有动作都不执行**，
> 且只在控制台留下一条 `配置异常` 日志。

### 6.5 变量替换

变量替换顺序是 **PAPI → `%input%` → `%player%`**，顺序不可颠倒。

```yaml
commands:
  - '[message] &a你好, %player_name%!'
  - '[console] give %player_name% %input%'
```

> ⚠️ 顺序不能反：玩家输入本身可能包含 `%`，先替换 `%input%` 会让 PAPI 误解析。

---

## 7. 条件表达式

格式：`左侧 运算符 右侧`，两侧都支持 PAPI 变量。

```yaml
conditions:
  - '%player_name% != 无'
  - '%player_level% >= 30'
conditionsHide:
  - '%player_money% < 100'
```

### 7.1 运算符

| 运算符 | 语义 |
|---|---|
| `>=` | 左 ≥ 右（数值） |
| `<=` | 左 ≤ 右（数值） |
| `!=` | 左 ≠ 右（字符串） |
| `==` / `=` | 左 = 右 |
| `>` | 左 > 右（数值） |
| `<` | 左 < 右（数值） |
| 无运算符 | 非空、非 `false`、非 `0` 即成立 |

### 7.2 语义要点

- **`conditions` 全部满足**才可点击；不满足则拒绝并提示。
- **`conditionsHide` 不满足则隐藏按钮**（静默，不提示）。
- 多条条件之间是 **AND** 关系。

> ⚠️ **两条铁律**：
>
> 1. **条件两侧都必须有值。** 右侧留空（如 `'%player_name% != '`）会被判定为
>    **条件不满足**，结果是按钮**对所有人隐藏** —— 很难从配置上看出来。
> 2. **数值解析失败视为"不满足"，而非"等于 0"。** 当 PAPI 变量不存在时，
>    替换结果是原样的 `%xxx%`；此时条件一律判为不满足，不会误放行。

### 7.3 限制

- **不支持 `!` 取反**
- **不支持 AND / OR 关键字**（多条并列即 AND）
- **不支持连续比较**（`a >= b >= c` 无效）

---

## 8. 限制系统：冷却与限购

### 8.1 `id` —— 限制的前提

**`id` 是冷却与限购的唯一身份标识，不能为 `0`。**

```yaml
- slot: 14
  material: DIAMOND
  id: -1          # ← 限制身份
  limit: 3
```

- `id` 必须**全局唯一** —— 重复会导致两个按钮共享同一个计数器。
- **推荐用负数**（`-1`、`-2`…）以避免与物品库 id 冲突。
- **`id: 0` 或未配置时，`cd` / `limit` / `cdHide` / `limitHide` 全部无效。**

> ⚠️ **修改带 `id` 的按钮时，先删掉 `id` 节点，改完再加回。**
> 否则旧的使用记录会与新配置错位，表现为"冷却时间莫名其妙"。

### 8.2 隐藏与执行是两回事

`limitHide` / `cdHide` **独立于** `action.limit` / `action.cd` 全局开关：

- `action.*` 控制**执行层**是否拦截
- `limitHide` / `cdHide` 控制**显示层**是否隐藏

即使全局关闭了次数限制，`limitHide` 仍然生效。这是刻意的设计。

### 8.3 记录时机

**校验通过后才记录使用。** 商店校验失败时**不会**消耗次数或冷却 ——
买不起的时候不应该扣次数。

### 8.4 清理限制数据

```
/exmenu clear <菜单|all> [玩家]
```

---

## 9. 商店与交易

```yaml
- slot: 16
  material: EMERALD
  name: '&a&l购买钻石'
  shopType: leftBuyRightSell
  shopMaterial: 'DIAMOND:1'
  shopPrice: '100'
  shopEconomy: money
```

### 9.1 `shopType`

| 取值 | 含义 |
|---|---|
| `leftBuyRightSell` | 左键买入，右键卖出 |
| `leftSellRightBuy` | 左键卖出，右键买入 |

> ⚠️ **仅支持上面这两种**。其他值（包括 `buy` / `sell`）会报 `配置异常` 并**阻止点击**。

### 9.2 属性

| 键 | 说明 |
|---|---|
| `shopMaterial` | 交易物品，格式 `材质:数量` |
| `shopPrice` | 价格，支持公式 |
| `shopNumber` | 数量，支持公式 |
| `shopGiveMaterial` | 交易完成后**额外**给予的物品，`材质:数量` |
| `shopEconomy` | 经济类型，默认 `money` |

### 9.3 公式

`shopPrice` / `shopNumber` 支持**公式**，变量 `input` 为玩家输入值：

```yaml
input: 'NUMBER'
shopPrice: '100 * input'
```

### 9.4 经济插件

| `shopEconomy` | 依赖 |
|---|---|
| `money`（默认） | Vault + 经济插件 |
| `point` | 点券（**未实现**，会被拒绝） |

---

## 10. 输入系统

让玩家在聊天栏输入一个值，再带着这个值执行动作。

```yaml
- slot: 20
  material: PAPER
  name: '&e&l购买'
  input: 'NUMBER'
  commands:
    - '[console] give %player_name% diamond %input%'
```

### 10.1 配置

| 键 | 说明 |
|---|---|
| `input` | 非空即触发输入流程。**既可写类型名，也可写提示文本** |
| `inputType` | 显式指定类型；不写时取 `input` 自身的值 |

| 类型 | 说明 |
|---|---|
| `STRING` | 任意文本，非空即可（默认） |
| `NUMBER` | 整数 |
| `POSITIVE_NUMBER` | 正数（大于 0） |

### 10.2 流程

**聊天通道（默认 `inputMethod: CHAT`）**

1. 玩家点击按钮
2. 关闭当前界面，提示输入
3. 玩家在聊天栏输入（**输入内容不会被其他玩家看到**）
4. 校验通过后执行原动作

**铁砧通道（`inputMethod: ANVIL`）**

```yaml
- slot: 20
  material: PAPER
  name: '&e&l购买'
  input: '请输入数量'
  inputType: 'POSITIVE_NUMBER'
  inputMethod: 'ANVIL'      # ← 弹铁砧界面而不是聊天栏
  commands:
    - '[console] give %player_name% diamond %input%'
```

1. 玩家点击按钮 → 弹出铁砧界面（输入框天然不进公共聊天）
2. 在重命名栏输入 → **点右侧输出格确认**；直接关界面 = 取消（回到来源菜单）
3. 校验规则与聊天通道完全一致（含 `%` 拒绝、类型校验），失败**不关界面**可重试

> 铁砧通道对新手更直观，也避开了"聊天消息被其它插件抢先处理"的整类问题。
> 两种通道的输出值都通过 `%input%` 参与替换，用法相同。

### 10.3 使用输入值

输入值通过 **`%input%`** 参与变量替换：

```yaml
commands:
  - '[message] &a你输入了 %input%'
  - '[console] give %player_name% diamond %input%'
```

### 10.4 退出与超时

**聊天通道**：

- 输入大写 **`T`** 退出输入
- 超过 `config.yml` 的 `inputTimeout` 秒未输入则自动取消

**铁砧通道**：直接关闭界面即取消，不使用 `T` 退出与超时机制。

> ⚠️ **含 `%` 的输入一律被拒绝** —— 否则玩家可以借此注入 PAPI 变量或命令占位符。

---

## 11. confirm.yml 与 item.yml

### 11.1 `gui/confirm.yml` —— 二次确认界面

按钮配 `confirm: true` 时弹出。

```yaml
size: 27
title: '&8确认操作'

confirm:
  material: LIME_STAINED_GLASS_PANE
  name: '&a&l确认'
  slot: 11
cancel:
  material: RED_STAINED_GLASS_PANE
  name: '&c&l取消'
  slot: 15
back:
  material: ARROW
  name: '&e&l返回'
  slot: 13
pane:
  material: GRAY_STAINED_GLASS_PANE
  slots: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 14, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26]
sound: BLOCK_NOTE_BLOCK_PLING
cancelSound: BLOCK_NOTE_BLOCK_BASS
```

| 键 | 说明 |
|---|---|
| `confirm` | **必需**，确认按钮 |
| `cancel` | 取消（返回来源菜单） |
| `back` | 返回来源菜单 |
| `pane` | 装饰板 |
| `sound` | 打开确认界面时的音效 |
| `cancelSound` | 点击取消时的音效 |

> **配置缺失时会退化为直接执行**，不会报错阻止动作。

### 11.2 `gui/item.yml` —— 物品库界面

`/exmenu item` 存入的物品在这里浏览。

```yaml
size: 54
title: '&8物品库'

item:
  name: '&e#%id% &7%material%'
  lore:
    - '&7id: &f%id%'
    - '&7点击取出'

previous:
  material: ARROW
  name: '&e上一页'
  slot: 45
next:
  material: ARROW
  name: '&e下一页'
  slot: 53
close:
  material: BARRIER
  name: '&c关闭'
  slot: 49
pane:
  material: GRAY_STAINED_GLASS_PANE
  slots: [46, 47, 48, 50, 51, 52]
```

`item.name` / `item.lore` 中的 `%id%` 会被替换为物品库 id，`%material%` 为材质名。

界面入口为 **`/exmenu library`**（浏览）与编辑器的"图标: 从物品库选"（选取模式）。
展示区为界面前 `size - 9` 格（最后一行留给翻页/关闭按钮）。

---

## 12. 语言文件

`languages/` 下的 `zh_CN.yml` / `zh_TW.yml` / `en_US.yml` 结构一致，键名相同。

```yaml
prefix: '&8[&bPlayerMenu&8] &r'

noPermission: '&c你没有权限执行此操作'
playerOnly: '&c该命令只能由玩家执行'
noCommandPermission: '&c你没有权限使用该命令'

reloadMsg: '&a配置已重载'
noMenu: '&c菜单不存在'
conditionFailure: '&c条件不满足'
configError: '&c配置异常: %reason%'

noTimeLimit: '&c请等待 &e%time% &c秒后再试'
```

### 12.1 键分组

| 分组 | 键 |
|---|---|
| 通用 | `prefix` `noPermission` `playerOnly` `noPlayerFailureMsg` `playerNotOnline` `noCommandPermission` |
| 重载 | `reloadMsg` `reloadFailureMsg` |
| 菜单 | `noMenu` `conditionFailure` `configError` |
| 限制 | `noTimeLimit` `clickOperatorReason` |
| 经济 | `noBalance` `money` `point` `buyMsg` `sellMsg` `noLimitItem` |
| 物品 | `noItem` `noNumber` `noPositiveNumber` `addMenuItemMsg` `getMenuItemMsg` `clearSucceedMsg` |
| 输入 | `noInput` `inputExit` `inputPrompt` `inputTimeout` |
| 时钟 | `clockFailureMsg` |

### 12.2 变量

消息里的 `%xxx%` 会被替换，如 `configError` 的 `%reason%`、`noTimeLimit` 的 `%time%`。

> ⚠️ **请勿改动键名** —— 键名对应代码里的 `LangUtil` 调用，改名会导致消息显示为 `<key>`。

---

## 13. 常见配置陷阱

按「踩坑频率」排序。

### 13.1 高频问题速查

| 现象 | 原因 | 解决 |
|---|---|---|
| 按钮完全不显示 | 没写 `slot` / `slots` | 补上槽位（**这是唯一必需字段**） |
| 按钮点击没反应 | `id` 重复，或 `slot` 被高 `priority` 按钮占用 | 检查 `id` 唯一性、调整 `priority` |
| 后半段命令不执行 | `commands` 里有**空行**或标签拼错 | 删空行；用 `/exmenu check` 定位 |
| CD / 限购无效 | `id` 为 `0` 或未配置 | 给按钮一个唯一的非零 `id` |
| 限购计数错乱 | 多个按钮共用同一个 `id` | 让每个按钮的 `id` 唯一 |
| 改完配置没生效 | 忘记 `/exmenu reload` | 执行 `/exmenu reload` |
| 按钮对所有人隐藏 | `conditions` 右侧留空 | 条件两侧都要有值 |
| 属性/附魔不显示 | `hideFlag` / `hideEnchant` 默认 `true` | 显式设为 `false` |
| 商店点了没反应 | `shopType` 写成了 `buy` / `sell` | 改用 `leftBuyRightSell` |
| 改数据库配置无效 | `storage.yml` 不支持热重载 | 重启服务器 |
| 所有 `openItem` 失效 | `config.yml` 配了 `clock.menu` | 置空 `clock.menu` 或设 `clock.enable: false` |
| 输入后没执行 | 输入内容含 `%` 被拒 | 提示玩家不要输入 `%` |

### 13.2 配置前必读

1. **`slot` / `slots` 是唯一必需字段** —— 缺了按钮会被静默丢弃。
2. **`commands` 不能有空行** —— 空行会让后面所有动作都不执行。
3. **`id` 是 CD / 限购的前提**，且必须**全局唯一**、**不能为 0**。
4. **改带 `id` 的按钮前，先删 `id`**，改完再加回。
5. **`hideFlag` / `hideEnchant` 默认 `true`**（默认隐藏）。
6. **`cd` 推荐带单位**（`5s` / `2m` / `1h`）。
7. **`shopType` 只支持 `leftBuyRightSell` / `leftSellRightBuy` 两种写法。**
8. **`clock.menu` 一旦填写，会覆盖所有 `openItem`。**
9. **`storage.yml` 改动必须重启服务器。**
10. **升级自旧版 PlayerMenu 时，配置与菜单文件都不能直接复用** —— 数据目录
    （`EXMenu` vs `PlayerMenu`）、菜单格式（`buttons` 列表 vs `menu` 映射）、
    配置键全部不同。

### 13.3 用 `/exmenu check` 自检

```
/exmenu check            # 校验全部菜单
/exmenu check <菜单>      # 校验单个菜单
```

它会一次性列出以下问题：

- 菜单不存在
- `size` 不是 9 的倍数
- `commands` 中存在空行
- `commands` 中标签无法识别
- `id` 与前面的按钮重复
- 槽位超出界面范围

> 💡 这些问题在运行时的表现都是"按钮没反应"，**极难排查** ——
> 改完菜单后跑一次 `/exmenu check` 是最省事的做法。

---

## 14. EXMenu 对外提供的 PAPI 变量

装了 PlaceholderAPI 后，EXMenu 自身会注册一个变量提供方（expansion），
把**限购 / 冷却状态**暴露成变量，用于按钮的动态名称 / 描述回显。

| 变量 | 含义 | 未配对应限制时 |
|---|---|---|
| `%exmenu_limit_remaining_<id>%` | 剩余可用次数 | `-` |
| `%exmenu_limit_used_<id>%` | 已用次数 | `0` |
| `%exmenu_cd_seconds_<id>%` | 冷却剩余秒数（不在冷却中为 0） | `0` |

`<id>` 是按钮的 `id`（可为负数），必须**全局唯一**。配合 `dynamicLore` 使用：

```yaml
buttons:
  - slot: 13
    material: DIAMOND
    id: -1
    limit: 3
    dynamicLore:
      - '&7今日剩余: &f%exmenu_limit_remaining_-1% &7次'
      - '&7冷却: &f%exmenu_cd_seconds_-1% &7秒'
    commands:
      - '[console] give %player_name% diamond 1'
```

> **实现说明**：这些变量走 `LimitCache`（只读快照 + 异步刷新的短时缓存）。
> 变量读取通常发生在渲染 Lore 的主线程，**绝不在读取路径做数据库 IO**。
> 剩余次数写入后缓存立即失效，下一次渲染即反映新值；冷却剩余秒数用
> `click_time` 现场计算，精度与墙钟一致，不受缓存陈旧影响。
> 未安装 PlaceholderAPI 时该 expansion 不注册，菜单功能不受影响。

---

**相关文档**：[指令文档](COMMANDS.md) ｜ [开发文档](DEVELOPMENT.md) ｜
[架构文档](ARCHITECTURE.md) ｜ [文档索引](README.md)
