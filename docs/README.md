# EXMenu 文档

> EXMenu ｜ 一款有点好用的玩家菜单插件

---

## 文档导航

| 文档 | 面向 | 内容 |
|---|---|---|
| [**配置文档**](CONFIGURATION.md) | 服主 / 配置者 | 全部配置项的完整说明：菜单结构、按钮属性、条件、商店、输入系统、界面配置、语言文件 |
| [**指令文档**](COMMANDS.md) | 服主 / 管理员 | 全部指令、权限节点、动态指令、Tab 补全、常见问题 |
| [**开发文档**](DEVELOPMENT.md) | 开发者 / 维护者 | **当前实现**的构建、分层、核心流程、扩展指南（新增指令/标签/属性/界面/存储） |
| [**架构文档**](ARCHITECTURE.md) | 开发者 / 维护者 | **当前实现**的架构速查：分层、数据流、线程模型、已知缺陷清单 |

**不确定该看哪篇？**

- 想**配一个菜单** → [配置文档](CONFIGURATION.md)
- 想知道**某条指令怎么用** → [指令文档](COMMANDS.md)
- 想**改源码 / 加功能** → [开发文档](DEVELOPMENT.md)（有完整扩展指南）
- 想**快速了解整体结构** → [架构文档](ARCHITECTURE.md)
- 想**排查菜单配置错误** → `/exmenu check`（见[指令文档](COMMANDS.md#exmenu-check-菜单)）

---

## 快速上手

### 1. 安装

把 `EXMenu-1.0.0.jar` 放入 `plugins/` 目录，重启服务器。

**可选依赖**（按需安装）：

| 插件 | 作用 |
|---|---|
| PlaceholderAPI | 变量支持 |
| Vault + 经济插件 | 金币功能 |

### 2. 打开默认菜单

```
/exmenu open example.yml
```

或在菜单里绑定 `openCommand` / 使用时钟物品（需在 `config.yml` 开启 `clock.enable`）。

### 3. 创建自己的菜单

**方式一：生成骨架后手动编辑**

```
/exmenu create 我的菜单
```

会在 `plugins/EXMenu/menu/我的菜单.yml` 生成一个可用的骨架，然后手动编辑。

**方式二：手动编辑 YAML**

在 `plugins/EXMenu/menu/` 下新建 `.yml` 文件：

```yaml
title: '&8我的菜单'
size: 27

buttons:
  - slot: 13
    material: DIAMOND
    name: '&a点我'
    lore:
      - '&7点击获得奖励'
    commands:
      - '[message] &a你好, %player_name%!'
      - '[console] give %player_name% diamond 1'
```

执行 `/exmenu reload` 生效。

### 4. 常用指令

```
/exmenu reload              重载配置（改完配置必执行）
/exmenu open <菜单名>        打开菜单
/exmenu create <菜单名>      生成菜单骨架
/exmenu check               校验全部菜单配置
/exmenu getMaterial         查看手持物品的材质名
```

完整列表见 [指令文档](COMMANDS.md)。

---

## 核心概念

| 概念 | 说明 |
|---|---|
| **菜单** | `menu/` 目录下的一个 `.yml` 文件，对应一个箱子界面 |
| **按钮** | 菜单中 `buttons:` 下的一个列表项，对应界面中的一个物品 |
| **`slot`** | 按钮所在的槽位，从 0 开始计数 |
| **`id`** | 按钮的唯一标识，**限购与冷却功能的前提** |
| **`commands`** | 点击按钮执行的动作列表，需以 `[类型]` 标签开头 |
| **条件** | 控制按钮是否显示或是否可点击的表达式 |

---

## 最容易踩的坑

以下几条按「踩坑频率」排序，详细说明见 [配置文档](CONFIGURATION.md)。

1. **改完配置必须 `/exmenu reload`**，否则不生效
2. **`id` 是限购/冷却的前提** —— 没有非零 `id`，`cd` 和 `limit` 全部无效
3. **`id` 必须全局唯一** —— 重复会导致按钮共享计数器
4. **`commands` 不能有空行** —— 空行会导致后续命令**全部不执行**
5. **`hideFlag` / `hideEnchant` 默认为 `true`**（默认隐藏属性与附魔）
6. **条件表达式两侧都必须有值** —— 右侧留空会被判定为不满足，按钮对所有人隐藏
7. **修改带 `id` 的按钮前先删掉 `id` 节点**，改完再加回

---

## 开发相关

从仓库根目录构建（聚合构建）：

```bash
mvn clean package
```

或直接构建模块：

```bash
cd newgen && mvn clean package
```

产物：`newgen/target/EXMenu-1.0.0.jar`

- 依赖全部来自**公开 Maven 仓库**，无需任何私有仓库凭据
- 目标字节码为 **Java 17**，支持 **Spigot 与 Folia**
- 架构与扩展说明见 [架构文档](ARCHITECTURE.md)

---

## 相关链接

- 使用教程 Wiki：<https://ricedoc.handyplus.cn/wiki/PlayerMenu/README/>
- SpigotMC 页面：<https://www.spigotmc.org/resources/117019/>
- 问题反馈：<https://github.com/LiStudioorg/EXMenu/issues>
