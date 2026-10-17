# EXMenu

[![发行版](https://img.shields.io/github/v/release/LiStudioorg/EXMenu)](https://github.com/LiStudioorg/EXMenu/releases)
[![构建](https://img.shields.io/github/actions/workflow/status/LiStudioorg/EXMenu/build.yml?branch=master)](https://github.com/LiStudioorg/EXMenu/actions)
![适用核心](https://img.shields.io/badge/适用核心-Spigot%20%7C%20Folia-blue)
![API](https://img.shields.io/badge/API-1.13%2B-blue)
![Java](https://img.shields.io/badge/运行时-Java%2017%2B-orange)
![许可证](https://img.shields.io/badge/license-AGPL--3.0--or--later-blue)

一款 **YAML 驱动**的 Minecraft 箱子菜单插件。服主用 YAML 描述「菜单长什么样、点了执行什么」，
插件负责渲染界面与执行逻辑。

[**English documentation at README.md**](README.md)

> **状态：预览版。** EXMenu 是 [PlayerMenu](https://github.com/handy-git/PlayerMenu) v1.11.0 的
> **完整重写**，去掉了所有私有框架依赖（HandyLib）。构建、打包、依赖 shade 与限购并发控制都已在
> CI 中验证，但插件**尚未在真实生产服务端加载运行过**，请先在测试服试用。

---

## 特性

- **纯 YAML 菜单** —— `menu/` 下每个文件即一个箱子界面，按钮是 `buttons:` 列表，用 `slot` / `slots` 定位
- **可视化编辑，不写 YAML 也能建菜单** —— `/exmenu edit` 在界面里点选生成按钮、动作链、限购、条件（自动写回 YAML，首次覆盖前留 `.bak` 备份原文）；`/exmenu settings` 把 `config.yml` 常用开关做成可点击按钮（**保留中文注释**）
- **22 种动作标签** —— `[message]` `[broadcast]` `[actionbar]` `[player]` `[console]` `[op]` `[performCommand]`
  `[dispatchCommand]` `[opPerformCommand]` `[close]` `[open]` `[title]` `[sound]` `[delay]` `[money]` `[point]`
  `[item]` `[effect]` `[heal]` `[feed]` `[exp]` `[action]`
- **冷却与限购** —— 由数据库原子闸门把关（`UPDATE ... WHERE number < ?`），连点也无法超发次数
- **EXMenu 对外提供 PAPI 变量** —— `%exmenu_limit_remaining_<id>%`、`%exmenu_cd_seconds_<id>%` 等，实时回显"今日剩余次数"
- **条件表达式** —— `>=` `<=` `!=` `==` `=` `>` `<`，两侧均支持 PlaceholderAPI 变量
- **商店** —— `leftBuyRightSell` / `leftSellRightBuy`，价格与数量支持含 `%input%` 的公式
- **聊天 / 铁砧两种输入** —— 向玩家取值（`inputMethod: CHAT|ANVIL`），`%input%` 注入动作链
- **二次确认界面** —— 按钮级 `confirm`
- **权限控制** —— 菜单级与按钮级 `permission` / `notPermission`
- **物品库界面** —— `/exmenu library` 浏览/取出存入的物品，编辑器从里面选图标
- **动态入口** —— 每个菜单可绑定命令（`openCommand`）与物品（`openItem`）；可选时钟物品；Shift+F
- **存储** —— SQLite（WAL，零配置）或 MySQL（HikariCP 连接池）
- **内置配置校验** —— `/exmenu check` 一次性列出空行、非法标签、重复 id、越界槽位
- **渲染异步化** —— 开菜单时批量查库移出游戏线程，支持 Folia
- **Spigot 与 Folia** —— 调度经平台抽象层（`Class.forName` 探测 Folia）

可选前置：**PlaceholderAPI**（变量）与 **Vault** + 经济插件（金钱动作、商店）。

---

## 快速上手

1. 把 `EXMenu-<版本>.jar` 放进 `plugins/`，重启服务器。
   首次启动会生成带注释的 `plugins/EXMenu/`（`config.yml`、`storage.yml`、语言文件、示例菜单）。
2. 打开示例菜单：

   ```
   /exmenu open example
   ```

3. 生成一个自己的菜单骨架：

   ```
   /exmenu create shop
   /exmenu reload
   ```

### 一个最小菜单（`plugins/EXMenu/menu/shop.yml`）

```yaml
title: '&8商店'
size: 27
openCommand:
  - shop

buttons:
  - slot: 13
    material: DIAMOND
    name: '&a&l领取钻石'
    id: -1          # 冷却/限购的前提，且必须全局唯一
    limit: 3
    cd: 5m          # 支持 5s / 2m / 1h（纯数字按毫秒）
    commands:
      - '[message] &a已领取！'
      - '[console] give %player_name% diamond 1'

  - slot: 15
    material: BARRIER
    name: '&c关闭'
    commands:
      - '[close]'
```

几个最常踩的坑：`commands` 里**绝对不能有空行**（会让后续动作全部不执行）；
`hideFlag` / `hideEnchant` **默认为 `true`**；条件**两侧都要有值** ——
`'%player_name% != '` 会让按钮对所有人隐藏。

---

## 指令

主指令 `/exmenu`（**无别名**），17 个子指令：

| 分组 | 子指令 |
|---|---|
| 玩家 | `open <菜单> [玩家]` · `close` · `clock` |
| 配置 | `reload` · `check [菜单]` · `settings` |
| 创作 | `create <菜单>` · `edit <菜单>` · `view <菜单>` |
| 物品库 | `item <槽位>` · `addItem` · `getItem` · `getMaterial` · `library` |
| 维护 | `clear <菜单\|all> [玩家]` · `convert` · `adminOpen <菜单> [玩家]` |

权限节点统一为 `exmenu.<子指令>` —— 完整参数与 Tab 补全见 [docs/COMMANDS.md](docs/COMMANDS.md)。

---

## 文档

| 文档 | 面向 | 内容 |
|---|---|---|
| [docs/README.md](docs/README.md) | 全部 | 文档索引与入门 |
| [docs/CONFIGURATION.md](docs/CONFIGURATION.md) | 服主 | 全部配置项、按钮属性、动作标签、条件、商店、输入 |
| [docs/COMMANDS.md](docs/COMMANDS.md) | 管理员 | 全部指令、权限、常见问题 |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | 开发者 | 构建、分层、核心流程、扩展指南、已知缺陷 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 开发者 | 架构速查、线程模型、已修复缺陷记录 |

---

## 构建

```bash
mvn clean package              # 仓库根目录（聚合构建）
# 或 cd newgen && mvn clean package
```

- 工具链：JDK 17+（CI 用 21）；所有依赖均来自**公开** Maven 仓库
- 产物：`newgen/target/EXMenu-1.1.0.jar`（约 16 MB，已 shade 并重定位 IFramework、XSeries）

## 发行

打 tag 推送即可，CI 会自动构建并发布 GitHub Release。**pom 版本号由 tag 覆盖，发行前无需改代码**：

```bash
git tag -a v1.2.0 -m "说明"
git push origin v1.2.0      # -> 自动发布 v1.2.0，产物 EXMenu-1.2.0.jar
```

含连字符的 tag（如 `v1.2.0-rc.1`）自动标记为预发布。

---

## 兼容性

| 要求 | 值 |
|---|---|
| 服务端 | Spigot 与 Folia（API 1.13+；Paper 未测试） |
| 服务器 Java | 17 及以上 |
| 可选 | PlaceholderAPI · Vault + 经济插件 |

### 从 PlayerMenu（旧版）迁移

EXMenu 是重写，**不是**无缝升级 —— 没有任何东西会自动迁移：

| 项目 | PlayerMenu v1.11.0 | EXMenu |
|---|---|---|
| 数据目录 | `plugins/PlayerMenu/` | `plugins/EXMenu/` |
| 命令 / 权限 | `/plm`、`playerMenu.*` | `/exmenu`、`exmenu.*` |
| 菜单格式 | `menu:` 按槽位编号的映射 | `buttons:` 列表，含 `slot` |
| 输入变量 | `%playerMenu_input%` | `%exmenu_input%` |

---

## 致谢

- 本项目是 [PlayerMenu](https://github.com/handy-git/PlayerMenu) v1.11.0（作者 **handy**）的完整重写，
  保留了其菜单 YAML 语义、动作标签与条件规则；底层私有依赖（HandyLib）全部替换为公开库。
- 内嵌库：[InventoryFramework](https://github.com/stefvanschie/Inventory-Framework)、
  [XSeries](https://github.com/CryptoMorin/XSeries)、HikariCP、sqlite-jdbc、item-nbt-api。

## 许可证

**Copyright © 2026 LiStudioorg。** EXMenu 采用 **GNU Affero General Public License v3.0
或更高版本（AGPL-3.0-or-later）**，全文见 [LICENSE](LICENSE)。

实际含义：

- 你可以自由使用、研究、修改、再分发 EXMenu —— 免费或收费均可。
- **任何修改版 / 衍生版的分发，都必须以同一许可证附带完整对应源码。
  闭源商用分发不被允许。**
- **把修改版放在玩家通过网络连接的服务器上运行，同样触发源码义务（AGPL 第 13 条）**——
  必须向这些玩家提供修改后的源码。这一条是刻意堵死「私服魔改、不分发、不开源」的漏洞，
  也就是 Minecraft 插件最常见的商用滥用方式。
- **赞助与付费授权：** 许可证约束除版权方以外的所有人。LiStudioorg 可以接受赞助、
  出售付费版本、或以其它条款单独授予商业许可（双重许可）。洽谈请开 GitHub Issue。
- shade 进 jar 的第三方库（IFramework、XSeries、HikariCP、sqlite-jdbc、item-nbt-api）
  仍受各自许可证约束。
