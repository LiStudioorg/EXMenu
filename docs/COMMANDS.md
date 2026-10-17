# EXMenu 指令与权限文档

> 面向**服主 / 管理员**。说明 **EXMenu**（`newgen` 模块）的全部指令与权限节点。
>
> 配置说明见 [配置文档](CONFIGURATION.md)，开发说明见 [开发文档](DEVELOPMENT.md)。

---

## 目录

1. [指令总览](#1-指令总览)
2. [权限节点](#2-权限节点)
3. [玩家指令](#3-玩家指令)
4. [管理员指令](#4-管理员指令)
5. [动态菜单指令](#5-动态菜单指令)
6. [仅玩家可用 / 控制台可用](#6-仅玩家可用--控制台可用)
7. [Tab 补全](#7-tab-补全)
8. [常见问题](#8-常见问题)

---

## 1. 指令总览

主指令 `/exmenu`（无别名）。

**参数约定**：`args[0]` 永远是子指令名，用户参数从 `args[1]` 开始。

| 子指令 | 语法 | 权限 | 默认 |
|---|---|---|---|
| `open` | `/exmenu open <菜单> [玩家]` | `exmenu.open` | **所有人** |
| `close` | `/exmenu close` | `exmenu.close` | **所有人** |
| `clock` | `/exmenu clock` | `exmenu.clock` | **所有人** |
| `reload` | `/exmenu reload` | `exmenu.reload` | OP |
| `create` | `/exmenu create <菜单>` | `exmenu.create` | OP |
| `view` | `/exmenu view <菜单>` | `exmenu.view` | OP |
| `item` | `/exmenu item <槽位>` | `exmenu.item` | OP |
| `addItem` | `/exmenu addItem <菜单> <按钮id> <槽位>` | `exmenu.addItem` | OP |
| `getItem` | `/exmenu getItem <id> [数量] [玩家]` | `exmenu.getItem` | OP |
| `getMaterial` | `/exmenu getMaterial` | `exmenu.getMaterial` | OP |
| `clear` | `/exmenu clear <菜单\|all> [玩家]` | `exmenu.clear` | OP |
| `convert` | `/exmenu convert` | `exmenu.convert` | OP |
| `adminOpen` | `/exmenu adminOpen <菜单> [玩家]` | `exmenu.adminOpen` | OP |
| `check` | `/exmenu check [菜单]` | `exmenu.check` | OP |

共 **14 个子指令**。

**`/exmenu` 无参数**时显示帮助，**不需要任何权限** —— 这样普通玩家打 `/exmenu`
能看到"你没权限"，而不是"未知命令"，反而更好排查。

子指令名**大小写不敏感**（内部统一转小写比较）。

---

## 2. 权限节点

全部权限节点统一为 `exmenu.<子指令>`。

| 权限节点 | 说明 | 默认 |
|---|---|---|
| `exmenu.*` | 全部权限（父节点） | op |
| `exmenu.open` | 打开菜单 | **true** |
| `exmenu.close` | 关闭菜单 | **true** |
| `exmenu.clock` | 取回菜单时钟 | **true** |
| `exmenu.reload` | 重载配置 | op |
| `exmenu.create` | 创建菜单 | op |
| `exmenu.view` | 预览菜单 | op |
| `exmenu.item` | 存入物品库 | op |
| `exmenu.addItem` | 设置按钮图标 | op |
| `exmenu.getItem` | 取出物品库物品 | op |
| `exmenu.getMaterial` | 获取材质名 | op |
| `exmenu.clear` | 清理限制数据 | op |
| `exmenu.convert` | 数据迁移 | op |
| `exmenu.adminOpen` | 管理员打开菜单 | op |
| `exmenu.check` | 校验菜单配置 | op |

### 2.1 通配符

`exmenu.*` 视为拥有**全部**权限，等同于同时持有一切子节点。

### 2.2 控制台

**控制台发送者直接放行所有权限判断。**

### 2.3 菜单级权限

菜单文件里的 `permission` 节点，**仅当 `config.yml` 的 `permission: true` 时生效**：

```yaml
# menu/vip.yml
permission: 'exmenu.open.vip'
```

留空或不写表示**无需权限**。

### 2.4 按钮级权限

按钮的 `permission` / `notPermission` 控制**按钮是否显示**：

```yaml
- slot: 10
  permission: 'group.vip'       # 需全部满足才显示
- slot: 12
  notPermission: 'group.vip'    # 命中则隐藏
```

> ⚠️ **切勿**把 `exmenu.*` 整体授予普通玩家 —— 其中包含 `item`、`addItem`
> 等可修改物品库与服务端文件的管理指令。

### 2.5 权限插件提示

使用 LuckPerms 等权限插件时，`default: op` 的语义由权限插件接管，
**OP 不一定自动获得权限**。建议显式授予：

```
/lp group admin permission set exmenu.* true
/lp group default permission set exmenu.open true
/lp group default permission set exmenu.close true
/lp group default permission set exmenu.clock true
```

---

## 3. 玩家指令

### `/exmenu open <菜单> [玩家]`

打开菜单。

- **权限**：`exmenu.open`
- **可选形态**：带玩家参数时，**需要额外持有 `exmenu.adminOpen`**，用于替其他玩家打开。

```
/exmenu open example.yml
/exmenu open example          # .yml 可省略
/exmenu open example Steve    # 替 Steve 打开（需 adminOpen 权限）
```

菜单名**大小写不敏感**，`.yml` 后缀可省略。

失败提示：`noMenu`（菜单不存在）、`playerNotOnline`（目标玩家不在线）、
`noPermission`（目标玩家无权限）。

### `/exmenu close`

关闭当前打开的界面。

- **权限**：`exmenu.close`
- 给"菜单卡住了"的玩家一个自救出口。

### `/exmenu clock`

取回时钟物品。

- **权限**：`exmenu.clock`
- 需要 `config.yml` 的 `clock.enable: true`，否则提示 `clockFailureMsg`。
- 物品放在 `clock.slot` 指定的槽位；**该槽位已有物品时改为放进背包**。

---

## 4. 管理员指令

### `/exmenu reload`

重载配置。

- **权限**：`exmenu.reload`
- 重载 `config.yml`、语言文件、`menu/` 下全部菜单、`gui/` 两个界面配置。
- **同时丢弃按钮缓存**（只刷新配置而不丢缓存，玩家会一直看到旧按钮）。
- ⚠️ **不会重载 `storage.yml`** —— 改数据库配置需**重启服务器**。

失败提示：`reloadFailureMsg`。

### `/exmenu create <菜单>`

创建菜单文件。

- **权限**：`exmenu.create`
- 生成一份**可直接使用的骨架**（标题、`size: 27`、一个示例按钮），而不是空文件。

```
/exmenu create shop
```

- 菜单名会自动补 `.yml`。
- **已存在时报错**，不会覆盖。
- 菜单名带 `../` 等**路径穿越写法会被拒绝**。
- 创建后会自动重新加载菜单列表，立即可用。

### `/exmenu view <菜单>`

以管理员视角预览菜单。

- **权限**：`exmenu.view`
- **跳过限制检查**（冷却、限购），其余渲染逻辑保持一致。
- 用于检查按钮的实际显示效果。

### `/exmenu item <槽位>`

把物品栏指定槽位的物品存进物品库。

- **权限**：`exmenu.item`
- 物品按**序列化内容的 MD5 去重**；已存在时直接返回既有 id，不重复存储。

```
/exmenu item 0
```

返回：`已存入物品库, id: <id>` 或 `该物品已存在, id: <id>`。

### `/exmenu addItem <菜单> <按钮id> <槽位>`

把手持物品设为某个按钮的图标。

- **权限**：`exmenu.addItem`

```
/exmenu addItem shop -1 14
```

它会：

1. 把手持物品存入物品库（已存在则复用 id）
2. 在 `menu/shop.yml` 中找到 `id` 匹配 `-1` 的按钮
3. 把该按钮的 `id` 改为物品库 id、`slot` 改为指定槽位
4. **移除该按钮的 `material`**（用物品库时必须清掉，否则渲染会优先用 `material`）

> ⚠️ 这里的 `<按钮id>` 是**按钮原有的 `id` 字段值**（通常为负数），
> 用于定位按钮；执行后它会被**替换成物品库 id**。

### `/exmenu getItem <id> [数量] [玩家]`

从物品库取出物品。

- **权限**：`exmenu.getItem`
- 数量默认 `1`，玩家默认自己。
- **为他人发放需要额外持有 `exmenu.adminOpen`**。

```
/exmenu getItem 1
/exmenu getItem 1 64
/exmenu getItem 1 64 Steve
```

> 💡 参数解析器会**先判断第三个参数能否当作数字**，所以
> `/exmenu getItem 5 Steve` 也能正确理解为"给 Steve 发 5 号物品"，
> 不会把 `Steve` 当成数量。

### `/exmenu getMaterial`

获取手持物品的材质名。

- **权限**：`exmenu.getMaterial`
- 用于确认 `material` / `openItem` / `shopMaterial` 该填什么。
- 输出纯文本，方便复制。

### `/exmenu clear <菜单|all> [玩家]`

清理限制记录（冷却与限购）。

- **权限**：`exmenu.clear`

```
/exmenu clear shop            # 清理 shop.yml 里所有按钮的限制记录
/exmenu clear all             # 清理全部限制记录
/exmenu clear shop Steve      # 只清理 Steve 在 shop.yml 上的记录
/exmenu clear all Steve       # 只清理 Steve 的全部记录
```

工作方式：扫描菜单里所有 `buttons` 的 `id`，清理这些 id 的限制记录
（**含负数 id**，它们同样是限制标识）。

### `/exmenu convert`

数据迁移说明。

- **权限**：`exmenu.convert`
- 当前实现只输出**迁移指引**（需要重启服务器后执行，且不清理源数据库）。
- 提示把旧库的 `player_menu` 表导出为 SQL，再用数据库工具导入 `menu_item` 表。

> ⚠️ **这不是自动迁移工具** —— 请务必先备份数据库。

### `/exmenu adminOpen <菜单> [玩家]`

管理员强制打开菜单。

- **权限**：`exmenu.adminOpen`
- **跳过限制检查**，与 `/exmenu view` 类似但可指定目标玩家。

```
/exmenu adminOpen shop
/exmenu adminOpen shop Steve
```

### `/exmenu check [菜单]`

**校验菜单配置。**

- **权限**：`exmenu.check`
- 不带参数时校验全部菜单。

```
/exmenu check
/exmenu check shop
```

检查项：

| 检查 | 说明 |
|---|---|
| 菜单不存在 | 文件缺失 |
| `size` 不是 9 的倍数 | 会提示已规整为多少 |
| `commands` 中存在空行 | 会指出第几行 |
| `commands` 中标签无法识别 | 会指出第几行与内容 |
| `id` 与前面的按钮重复 | 会指出重复的 id |
| 槽位超出界面范围 | 会指出越界的槽位号 |

输出示例：

```
[PlayerMenu] shop.yml 发现 2 个问题:
  - 按钮 #3.id=-1 与前面的按钮重复
  - 按钮 #5.commands 第 2 行为空，会中断整条动作链
[PlayerMenu] 共 2 个问题
```

> 💡 **这是最省事的排错手段。** 上述问题在运行时的表现都是"按钮没反应"，
> 靠肉眼对着配置猜非常困难。改完菜单后跑一次 `/exmenu check`。

---

## 5. 动态菜单指令

菜单文件里配置 `openCommand` 后，玩家可直接用该命令打开菜单：

```yaml
# menu/shop.yml
openCommand:
  - shop
  - sh
```

玩家输入 `/shop` 或 `/sh` 即可打开。

### 特性

| 特性 | 说明 |
|---|---|
| **不用写 `/`** | 配置里只写命令名 |
| **可绑多个** | 一个菜单可绑定多条命令 |
| **大小写不敏感** | 命令名以小写比较 |
| **权限沿用菜单的 `permission`** | 动态指令**没有独立的权限节点** |
| **加载即生效** | 启动与 `/exmenu reload` 时注册 |

命令名冲突时行为不可预期，建议使用独特前缀（如 `exmenushop`、`servermenu`）。

---

## 6. 仅玩家可用 / 控制台可用

### 仅玩家可用

以下子指令在控制台执行时会提示 `playerOnly`：

`close` `clock` `create` `view` `item` `addItem` `getMaterial` `adminOpen`

`open` 与 `getItem` 在**不带玩家参数**时也仅限玩家。

### 控制台可用

`reload` `clear` `convert` `check`，以及**带玩家参数**的 `open` / `getItem` / `adminOpen`。

---

## 7. Tab 补全

| 参数位置 | 补全内容 |
|---|---|
| `args[1]`（子指令名） | 全部子指令，**按权限过滤**（无权限的不显示） |
| `args[2]`（菜单名） | `open` / `view` / `adminOpen` / `addItem` / `clear` / `check` |
| `args[2]`（其他） | `clear` 额外补全 `all` |
| `args[3]` | `open` / `clear` / `adminOpen` / `getItem` 返回空（不干扰玩家名输入） |

菜单名补全**不带 `.yml` 后缀**。

---

## 8. 常见问题

### 8.1 指令相关

| 现象 | 原因 | 解决 |
|---|---|---|
| `/exmenu` 无任何输出 | 只显示帮助，属正常 | 直接用具体子指令 |
| 子指令提示"你没有权限使用该命令" | 缺对应权限节点 | 检查权限插件 |
| 动态指令 `/shop` 没反应 | 未 `/exmenu reload`，或与其他插件命令冲突 | 重载；改用独特命令名 |
| `/exmenu open` 提示菜单不存在 | 文件名不对 | 用 `/exmenu check` 或看 `menu/` 目录实际文件名 |
| `/exmenu getItem` 控制台报错 | 未指定玩家 | 加上玩家参数 |
| `/exmenu create` 提示菜单已存在 | 同名文件已存在 | 换个名字或手动编辑 |
| `/exmenu create` 提示菜单名非法 | 含 `../` 等路径穿越写法 | 用普通文件名 |

### 8.2 权限相关

| 现象 | 原因 | 解决 |
|---|---|---|
| OP 也无法执行 | 权限插件接管了 `default: op` | 用 `/lp` 显式授予 |
| 玩家能看到不该看的按钮 | 按钮未配 `permission` | 给按钮加 `permission` |
| 玩家能修改菜单文件 | 错误授予了 `exmenu.*` 或 `addItem` | 收紧权限 |

### 8.3 排错流程

1. **改完配置先 `/exmenu reload`** —— 忘了这步是最常见的原因。
2. **跑 `/exmenu check`** —— 它会指出标签错误、空行、id 重复、槽位越界。
3. **确认菜单名** —— `/exmenu check` 列出的就是实际加载到的菜单。
4. **确认权限节点拼写** —— `exmenu.<子指令>`，大小写敏感。

---

**相关文档**：[配置文档](CONFIGURATION.md) ｜ [开发文档](DEVELOPMENT.md) ｜
[架构文档](ARCHITECTURE.md) ｜ [文档索引](README.md)
