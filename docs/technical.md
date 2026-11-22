# Hearthold — technical documentation

[← Back to the overview](../README.md) · [Design document](design.md)

Hearthold is a Paper 26.3 plugin (Java 25, SQLite) for clan-war survival servers: block HP instead of claims, wars that end in vassalage, trade routes with manual caravans, weight-based logistics and a built-in web map. This page covers installation, building, configuration and commands. The full game rules are in the [design document](design.md).

## Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Building from source](#building-from-source)
- [Local test server](#local-test-server)
- [Configuration](#configuration)
- [Languages](#languages)
- [Commands and permissions](#commands-and-permissions)
- [Project layout](#project-layout)
- [Documentation](#documentation)
- [Contributing](#contributing)
- [License](#license)

## Features

Every feature is reachable from in-game menus (the **Hearthold compass** and the **base heart menu**); commands are optional shortcuts. Full rules live in the [design document](design.md).

| Area | What it does |
| --- | --- |
| **Base heart, nodes, proxies** | A heart block claims a sphere of land; nodes extend it, proxies are fragile outposts anywhere on the map. New spheres activate with a delay and neighbours are notified. → [design](design.md#protection-core) |
| **Block HP** | No hard claims: protected blocks have HP, snap back after a hit and slowly regenerate. Damage depends on the tool, tool wear equals HP removed. Wilderness blocks have HP too, so even wood and cobble are worth something. → [design](design.md#block-hp) |
| **Prime time** | Outside prime time any hit on a foreign base removes 1 HP and wears tools ×10 — offline raids don't pay. |
| **War, vassalage, shields** | Sieges require a public war declaration (server-wide war journal, war fatigue). Standing at the enemy heart in prime time captures it: the clan becomes your vassal for 14 days and pays 20% tribute. Beaten bases get a raid shield. Patronage lets a clan sponsor a fresh start for a wiped one. → [design](design.md#war-vassalage-and-shields) |
| **Alliances** | Mutual alliances with a defender bonus on allied land — but every ally needs its own war to siege. |
| **Economy & upkeep** | Hearts store emeralds for daily upkeep and accumulate energy from chunks. Unpaid or abandoned bases can be captured without war. A universal buyer NPC turns loot into emeralds; death drops only part of your items. → [design](design.md#economy) |
| **Resource collectors** | Multi-block collectors produce the resource of their region; chunk energy depletes and recovers. Resources are spread across regions, so no clan produces everything. |
| **Weight & logistics** | Inventory weight slows you down. Horses, carts, minecarts and a carrying-capacity potion matter again. → [design](design.md#weight-and-logistics) |
| **Trade routes & caravans** | Clans sign trade routes in a book, ship goods by caravan through the treasury, and missed deliveries hit public reputation. → [design](design.md#trade-routes) |
| **Timed crafting** | Smelting, brewing and crafting stations take real time. → [design](design.md#timed-crafting) |
| **Plots & prestige** | Plot hearts split a base into member plots with their own access; builds can be rated for clan prestige. |
| **Newbie protection** | A clan's first base is cheap and can't be broken for 5 days (smaller radius, no nodes, limited containers); placing a proxy ends it early. → [design](design.md#newbie-protection-and-proxies) |
| **Bodies & compass** | A player who logs out stays in the world as a sleeping body that others can kill and loot; only bodies on your own base are hidden from compasses. The compass points to bases, bodies and events and opens every menu. → [design](design.md#bodies-and-the-compass) |
| **Events** | Admin-placed event structures with status and timers. |
| **Roles & tags** | Leaders give members tags (builder, quartermaster, …) that grant specific rights such as chest access or placing nodes. |
| **Web portal** | Built-in website with the live map (bases, events, players outside bases depending on clan **scouting** level), war journal, trade routes, prime time and an admin panel. Players log in with `/site`. → [design](design.md#web-portal-and-map) |
| **HUD** | Side panel showing your weight, tool durability, whose land you're on and your relation to it, chunk energy and more. |

## Requirements

- **Paper 26.3** server (other forks of Paper may work but are untested; Spigot/Bukkit are not supported).
- **Java 25** to run the server and to build the plugin.
- SQLite is bundled via Paper's library loader — no external database needed.

## Installation

1. Download `hearthold-<version>.jar` from the [Releases](../../../releases) page (or [build it yourself](#building-from-source)).
2. Put it into your server's `plugins/` folder.
3. Start the server once. Hearthold creates `plugins/Hearthold/config.yml` and its database `hearthold.db`.
4. Adjust [the config](#configuration) — at least `language`, the `map` border and `web.public-url` — then run `/hearthold reload` or restart.
5. Optionally pre-generate the world inside the border: `/hearthold pregen start <world>`.

## Building from source

```bash
git clone https://github.com/<owner>/hearthold.git
cd hearthold
./gradlew build          # Windows: gradlew.bat build
```

The plugin jar is written to `build/libs/hearthold-<version>.jar`. Gradle needs a JDK 25 (via `JAVA_HOME` or `PATH`); the wrapper downloads Gradle itself.

## Local test server

For development there is a one-click loop on Windows:

1. Download a Paper 26.3 jar from [papermc.io](https://papermc.io/downloads/paper) and save it as `run/paper.jar`.
2. Run the server once, read the [Minecraft EULA](https://aka.ms/MinecraftEULA) and set `eula=true` in `run/eula.txt`.
3. Run [`start-server.bat`](../start-server.bat): it builds the plugin, copies it into `run/plugins/` and starts the server in the same console (type `stop` to shut it down).

`run/` is git-ignored, so worlds and test data stay local. The script uses `JAVA_HOME` if set, otherwise `java` from `PATH`.

## Configuration

Everything lives in `plugins/Hearthold/config.yml`, fully commented. Main sections:

| Section | Controls |
| --- | --- |
| `language` | Plugin language: `en` (default) or `ru` |
| `heart`, `node`, `proxy`, `territory`, `base-levels` | Sphere radii, costs, activation delay, base levels and HP |
| `block-health`, `wilderness`, `prime-time`, `defender-bonus`, `siege` | Block HP, regeneration, tool damage, prime-time schedule, capture rules |
| `upkeep`, `energy`, `buyer`, `death` | Economy, chunk energy, buyer prices, death penalties |
| `map`, `regions`, `neutral-zones`, `collector`, `ore-respawn` | World border, resource regions, canyons and shared mines, collectors |
| `weight`, `trade`, `craft`, `plot` | Weight limits, trade routes and caravans, crafting times, plots |
| `war`, `vassalage`, `patronage`, `newbie`, `events` | War fatigue, tribute, patronage, newbie protection, events |
| `web` | Built-in web portal: `enabled`, `bind`, `port` (default `8124`), `public-url` |

Numbers are starting values and are expected to be balanced on a live server.

## Languages

Hearthold ships with **English** (default) and **Russian**. Set the server language in `config.yml`:

```yaml
language: en   # or ru
```

Chat messages, menus, items, books, the HUD and the web portal all follow this setting. To tweak any text, copy a file from [`src/main/resources/lang/<language>/`](../src/main/resources/lang) to `plugins/Hearthold/lang/<language>/` and edit only the keys you want to change — your file overrides the bundled one. Missing keys fall back to English.

## Commands and permissions

| Command | Description |
| --- | --- |
| `/compass` | Get the Hearthold compass; right-click opens the main menu |
| `/clan …` | Create, invite, accept, leave, info, heart, node, collector, proxy, ally, trust |
| `/plot …` | Plots inside a base and build rating |
| `/war …` | Declare war, peace, status, war journal, vassals, tribute, patronage |
| `/trade` | Trade routes, treasury, caravans |
| `/weight` | How much your load weighs |
| `/event` | Events: places, status, timers |
| `/site` | Login link for the web portal; `/site scouting <0-3>` sets clan scouting |
| `/hearthold …` (alias `/hh`) | Admin: `reload`, `hp`, `prime`, `zones`, `energy`, `upkeep`, `buyer`, `region`, `ores`, `pregen`, `potion`. Without arguments opens the admin menu |

| Permission | Default | Grants |
| --- | --- | --- |
| `hearthold.admin` | op | Admin command, admin menu and admin web panel |

## Project layout

```
src/main/java/dev/hearthold/
  base/        base heart, nodes, proxies, territory, base levels
  health/      block HP, regeneration, prime time, damage display
  clan/ war/   clans, alliances, wars, vassalage, capture
  economy/     upkeep, energy, buyer, death
  trade/       trade routes, caravans, treasury, stables
  collector/   resource collectors and chunk resources
  weight/      inventory weight
  plot/ craft/ event/ newbie/ body/ map/
  ui/          menus, compass, HUD, admin menu
  web/         built-in web portal and JSON API
  i18n/        Lang — translations
src/main/resources/
  config.yml   plugin.yml   lang/en, lang/ru   web/
docs/          design document
```

## Documentation

| Document | Contents |
| --- | --- |
| [design.md](design.md) | Design document — the single source of truth for mechanics |
| [AGENTS.md](../AGENTS.md) | Contribution rules for humans and AI agents: commit format, localization, UI-first |

## Contributing

Issues and pull requests are welcome. Before opening a PR, read [AGENTS.md](../AGENTS.md): every commit ends with a `Design-doc:` line, and any rule change must be reflected in [docs/design.md](design.md) in the same commit. New player-facing text goes through `Lang.tr` with keys in both `lang/en` and `lang/ru`.

## License

Hearthold is released under the [MIT license](../LICENSE).
