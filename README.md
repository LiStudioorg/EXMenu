# EXMenu

[![Release](https://img.shields.io/github/v/release/LiStudioorg/EXMenu)](https://github.com/LiStudioorg/EXMenu/releases)
[![Build](https://img.shields.io/github/actions/workflow/status/LiStudioorg/EXMenu/build.yml?branch=master)](https://github.com/LiStudioorg/EXMenu/actions)
![Core](https://img.shields.io/badge/core-Spigot%20%7C%20Folia-blue)
![API](https://img.shields.io/badge/api-1.13%2B-blue)
![Java](https://img.shields.io/badge/runtime-Java%2017%2B-orange)
![License](https://img.shields.io/badge/license-AGPL--3.0--or--later-blue)

A YAML-driven chest-menu plugin for Minecraft servers. Server owners describe what a menu looks
like and what a button does in plain YAML files; the plugin renders the GUI and executes the logic.

[**中文文档见 README-CH.md**](README-CH.md)

> **Status: preview.** EXMenu is a complete rewrite of [PlayerMenu](https://github.com/handy-git/PlayerMenu)
> v1.11.0 with all private-framework dependencies removed. The build, packaging, dependency shading and
> the limit/counter concurrency behavior are verified in CI, but the plugin has **not yet been
> load-tested on a production Minecraft server**. Test on a staging server first.

---

## Features

- **Pure-YAML menus** — every file in `menu/` is one chest GUI; buttons are a `buttons:` list with `slot` / `slots`
- **16 action tags** — `[message]` `[player]` `[console]` `[op]` `[performCommand]` `[dispatchCommand]`
  `[opPerformCommand]` `[close]` `[open]` `[title]` `[sound]` `[delay]` `[money]` `[point]` `[item]` `[action]`
- **Cooldowns & per-player purchase limits** — enforced by an atomic database gate
  (`UPDATE ... WHERE number < ?`), so rapid clicks cannot over-spend a limit
- **Condition expressions** — `>=` `<=` `!=` `==` `=` `>` `<` with PlaceholderAPI variables on both sides
- **Shops** — `leftBuyRightSell` / `leftSellRightBuy`, prices and quantities as formulas with `%input%`
- **Chat-input flow** — ask the player for a value, `%input%` substitutes it into the action chain
- **Confirm dialogs** — per-button second confirmation GUI
- **Permission control** — menu-level, per-button `permission` / `notPermission`
- **Dynamic entries** — bind commands (`openCommand`) and items (`openItem`) per menu; optional clock item; Shift+F
- **Storage** — SQLite (WAL, zero-config) or MySQL (HikariCP pools)
- **Built-in config validator** — `/exmenu check` lists blank action lines, unknown tags, duplicate ids
  and out-of-range slots in one pass
- **Spigot & Folia** — scheduling goes through a platform abstraction (`Class.forName` Folia detection)

Optional integrations: **PlaceholderAPI** (variables) and **Vault** + an economy plugin (money actions, shops).

---

## Quick start

1. Drop `EXMenu-<version>.jar` into `plugins/` and restart the server.
   First launch generates `plugins/EXMenu/` with a commented `config.yml`, `storage.yml`,
   language files and the sample menu.
2. Open the sample menu:

   ```
   /exmenu open example
   ```

3. Create your own menu (generates a working skeleton file):

   ```
   /exmenu create shop
   /exmenu reload
   ```

### A minimal menu (`plugins/EXMenu/menu/shop.yml`)

```yaml
title: '&8Shop'
size: 27
openCommand:
  - shop

buttons:
  - slot: 13
    material: DIAMOND
    name: '&a&lClaim 1 diamond'
    id: -1          # required for cd / limit — must be globally unique
    limit: 3
    cd: 5m          # units supported: 5s / 2m / 1h (bare number = ms)
    commands:
      - '[message] &aReceived!'
      - '[console] give %player_name% diamond 1'

  - slot: 15
    material: BARRIER
    name: '&cClose'
    commands:
      - '[close]'
```

Notes that bite most often: never leave a blank line inside `commands` (it aborts the whole chain),
`hideFlag` / `hideEnchant` default to `true`, and both sides of a condition need a value —
`'%player_name% != '` hides the button from everyone.

---

## Commands

Main command `/exmenu` (no aliases), 14 subcommands:

| Group | Subcommands |
|---|---|
| Players | `open <menu> [player]` · `close` · `clock` |
| Config | `reload` · `check [menu]` |
| Authoring | `create <menu>` · `view <menu>` |
| Item library | `item <slot>` · `addItem` · `getItem` · `getMaterial` |
| Maintenance | `clear <menu\|all> [player]` · `convert` · `adminOpen <menu> [player]` |

Permissions are `exmenu.<subcommand>` — full table, parameters and tab-completion:
[docs/COMMANDS.md](docs/COMMANDS.md).

---

## Documentation

| Doc | Audience | Contents |
|---|---|---|
| [docs/README.md](docs/README.md) | everyone | documentation index & first steps |
| [docs/CONFIGURATION.md](docs/CONFIGURATION.md) | server owners | every config key, button property, action tag, condition, shop, input |
| [docs/COMMANDS.md](docs/COMMANDS.md) | admins | all commands, permissions, FAQ |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | developers | build, layering, flows, extension guides, known issues |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | developers | architecture quick reference, threading, fixed-defect log |

---

## Building

```bash
mvn clean package              # from the repo root
# or: cd newgen && mvn clean package
```

- Toolchain: JDK 17+ (CI builds on 21); all dependencies come from **public** Maven repositories
- Artifact: `newgen/target/EXMenu-1.1.0.jar` (~16 MB, shaded: IFramework, XSeries relocated)

## Releasing

Push a tag — CI builds it and publishes a GitHub Release automatically. The pom version is
**overridden by the tag**, so no code change is needed before releasing:

```bash
git tag -a v1.2.0 -m "notes"
git push origin v1.2.0      # -> Release v1.2.0 with EXMenu-1.2.0.jar
```

Tags containing a hyphen (`v1.2.0-rc.1`) are published as pre-releases.

---

## Compatibility

| Requirement | Value |
|---|---|
| Server software | Spigot and Folia (API 1.13+; Paper untested) |
| Java runtime on the server | 17 or newer |
| Optional | PlaceholderAPI · Vault + economy provider |

### Migrating from PlayerMenu (legacy)

EXMenu is a rewrite, **not** a drop-in update — nothing migrates automatically:

| Item | PlayerMenu v1.11.0 | EXMenu |
|---|---|---|
| Data folder | `plugins/PlayerMenu/` | `plugins/EXMenu/` |
| Command / permissions | `/plm`, `playerMenu.*` | `/exmenu`, `exmenu.*` |
| Menu format | `menu:` map keyed by slot | `buttons:` list with `slot` |
| Input variable | `%playerMenu_input%` | `%exmenu_input%` |

---

## Credits

- This project is a full rewrite of [PlayerMenu](https://github.com/handy-git/PlayerMenu) v1.11.0
  by **handy**, whose business semantics (menu YAML meaning, action tags, condition rules) it preserves.
  All private-framework dependencies (HandyLib) were replaced with public libraries.
- Bundled libraries: [InventoryFramework](https://github.com/stefvanschie/Inventory-Framework),
  [XSeries](https://github.com/CryptoMorin/XSeries), HikariCP, sqlite-jdbc, item-nbt-api.

## License

**Copyright © 2026 LiStudioorg.** EXMenu is licensed under the
**GNU Affero General Public License v3.0 or later (AGPL-3.0-or-later)** — full text in [LICENSE](LICENSE).

In practical terms:

- You may use, study, modify and redistribute EXMenu — gratis or for a fee.
- **Any distribution of a modified or derivative build must ship the complete corresponding
  source under the same license. Closed-source commercial redistribution is not allowed.**
- **Running a modified version on a server that players connect to over the network also
  triggers the source obligation (AGPL §13)** — those users must be offered the modified
  source. This deliberately closes the "host a fork, never distribute, never release"
  loophole, the common commercial abuse pattern for Minecraft plugins.
- **Sponsorship & paid licensing:** the license binds everyone *except* the copyright holder.
  LiStudioorg may accept sponsorships, sell paid builds, or grant separate commercial
  licenses under different terms (dual licensing). Inquiries: open a GitHub issue.
- Third-party libraries shaded into the jar (IFramework, XSeries, HikariCP, sqlite-jdbc,
  item-nbt-api) remain under their own licenses.
