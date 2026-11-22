# Hearthold — design document

Current as of 11.11.2026. This file is the only place the design is maintained.

## Vision

Hearthold makes clans depend on each other: every block, resource and caravan costs effort, so trading with neighbours pays off better than destroying them.

The problem of the previous season: a clan could be completely self-sufficient. In 2 hours of Nether events a group of 3 got protection level 4, a week of upkeep and diamond-netherite gear. After that there was no reason to keep neighbours alive, the map emptied out and no alliances formed.

Season principles:

- **Slow progress.** Mining, building, crafting and hauling take time; a town grows like a medieval one — first a keep and wooden houses, then an inner wall; an outer wall is a major project.
- **Interdependence.** Resources are spread across regions; no clan produces everything on its own.
- **Logistics creates encounters.** Mines, canyons, caravans and rails create spots where players from different clans cross paths.
- **Vanilla mechanics gain economic meaning.** Horses, llamas, minecarts, redstone and gold become part of mining and hauling.
- **Reputation matters.** Broken agreements are visible to the whole server.

## Protection core

There are no full claims: territory is protected by spheres that give blocks HP, and a siege is only possible during a declared war and ends in vassalage, not in the destruction of the base.

### Base heart and nodes

- **Base heart** (also called the town or clan heart) — a block with a spherical radius. It stores upkeep emeralds, accumulates energy and holds the list of allies and leaders.
- **Node** — an extra sphere that extends the clan's main base. It can only be placed inside existing territory, adjacent to it. Indestructible. Placed by the leader and «Builders», removed only by the leader. The leader buys it for emeralds from the inventory (16 by default) and pays its upkeep from the heart. Not allowed during newbie protection.
- **Proxy** — a separate extra base (outpost) anywhere on the map, not necessarily near the heart. Expensive (64 emeralds and +6 per day by default), activates with a delay and is vulnerable right away: **it breaks under siege** — during a war the proxy block loses HP like any base block, and then its sphere disappears. A proxy's sphere cannot intersect any sphere of its own clan (heart, nodes, other proxies): it is a separate base, not an extension. Placing a proxy ends newbie protection (see «Proxies and new bases»).
- The heart menu has a «Nodes and proxies» section: how they differ, how many are placed and how many are allowed, purchase, and a list of all clan spheres. Each sphere has a compass, a highlight (the sphere boundary drawn with particles for 15 s) and, for nodes and proxies, removal by the leader with confirmation. A removed block is not returned and the protection around it disappears.
- **Connectivity.** Every node must be linked to the heart or a proxy by a chain of intersecting spheres. A node or proxy cannot be removed if some node would lose its link afterwards (e.g. heart → node 1 → node 2: node 1 cannot be removed until node 2's sphere intersects the heart).
- **Activation.** A new sphere (heart, node, proxy) activates with a delay. The territory belongs to the clan immediately: members build fast and break freely, and other clans cannot place spheres nearby. But until activation the zone has no block regeneration, no base block HP (wilderness HP applies) and no defender bonus. The remaining time is shown by right-clicking the sphere block with an empty hand, on inspection and in the sphere list.
- **The heart and nodes are indestructible.**
- **Costs and sizes (starting values, config.yml):** a clan's first heart is free, the next one costs 32 emeralds from the inventory; heart radius 32, node radius 16, up to 4 nodes. Upkeep: heart 8, node 4 emeralds per day. Spheres cannot touch other clans' spheres or neutral zones (canyons, shared mines). Clans whose spheres are closer than 256 blocks are notified about a new sphere.
- **Explosions** don't break blocks on clan territory (explosion HP damage — later).
- **Base levels.** A protected block's HP is the same across the whole clan territory and is set by the base level, not stacked by the number of spheres. Every base starts at level I. The leader raises it in the heart menu for emeralds from the treasury; each level adds to daily upkeep. Starting values: I — 100 HP; II — 160 HP, 96 emeralds, +2 per day; III — 250 HP, 256 emeralds, +5 per day.
- **The defender bonus** applies across the whole clan territory (which effects exactly is an open question).

The old model from Minto (level = number of intersecting spheres, up to ×5 HP, invulnerability at level 5, raids by removing nodes) is not used in Hearthold.

### Block HP

- Hitting a protected block removes HP; the block snaps back and its remaining HP is shown above it.
- Damage depends on the tool and prime time. The tool loses as much durability as the HP it removed: removing 10 HP costs 10 durability (plus a config multiplier, 0 by default).
- On each hit a «−N» pops up above the block in the colour of the tool's material (wood, stone, copper, iron, gold, diamond — light blue, netherite).
- Wilderness HP is based on vanilla block hardness (hardness × 8, from 3 to 60). Grass, flowers and leaves break as in vanilla.
- HP slowly regenerates to the maximum (with a pause after a hit). A new block starts at 1 HP and grows right away: a wall cannot be patched instantly during a siege.
- Right-clicking with an empty hand or an ordinary tool shows (only to you) the block's HP and whose territory it is, plus the outlines of that base's spheres in particles. Your own blocks' HP is visible too — so you can see how badly the enemy damaged them.
- Damaged blocks of your own base nearby are highlighted to clan members with particles (yellow, orange, red by remaining HP): you can see where someone tried to break in.
- A plot heart gives no HP.

### Prime time

- **Outside prime time** any hit on a foreign base removes 1 HP, and tools wear 10 times faster (prime-time.off-prime-wear). Offline raids don't pay.
- **During prime time** damage is full, but a raid needs an inventory full of pickaxes.

### Heart and new bases

- An attacker who reaches the heart during a war makes the clan a vassal instead of destroying it (see «War, vassalage and shields»).
- A new heart activates with a delay; neighbouring clans are notified.
- A typical siege: an outpost near the target — chests, gear sets, pickaxes, beds to respawn.

## War, vassalage and shields

A siege is only possible after a public declaration of war, and victory gives you a vassal for 14 days instead of a destroyed clan. A living neighbour brings more than a dead one.

### War

- **Without a declared war** PvP is allowed, but sieges don't work: blocks on another clan's base don't lose HP.
- **Declaring war** notifies the whole server and is recorded in the public war journal. The journal shows the current political situation and is open to everyone.
- **War fatigue** grows with every declared war: the attacker's upkeep rises and block HP regeneration on its bases slows down.
- Prime time still applies: outside it a hit removes 1 HP even during a war.
- **Who can declare on whom:** the leader declares war. Not allowed against an ally, a clan without a base, a clan with a shield, your own vassal or suzerain. Declaring war removes the attacker's own shield. Only the two warring clans can siege each other: allies need their own war.
- **End of war:** heart capture, peace agreed by both sides, the attacker's retreat, or automatically after 72 h.
- **Current fatigue:** +1 per declaration, −1 per 7 days; each point means upkeep ×1.25 and base HP regeneration ÷1.25 (the formula is an open question).
- **Raids and chests:** a foreign chest cannot be opened even during a war — it can only be broken by HP like any base block, and its contents drop.

### Vassalage

**Heart capture** works like a capture point (numbers are a proposal):

- Works only during a declared war and in prime time.
- The attacker stands within ~3 blocks of the heart; a progress bar is visible above the heart and to all defenders.
- Duration — e.g. 2–5 minutes: enough for defenders to run back from respawn.
- If a living defender is within the radius, progress pauses. If no attacker is within the radius, progress gradually resets.
- To reach the heart you have to break through walls with HP — that is the main part of a siege.
- The heart PIN has been removed.
- Currently: radius 3, 180 s, prime time only; without attackers progress decays by 1 s per second; the bar is a boss bar for both clans. The defeated clan gets a 48 h shield.
- **Capture is visible and audible:** while a capture is in progress, a red particle ring glows along the capture radius, a pillar of fire rises above the heart, and a bell rings every second (higher pitch as progress grows). If the capture is paused or decaying — a grey ring and smoke. At the moment of capture — fireworks, a totem flash and thunder.
- **A base without upkeep** (or an abandoned one) can be captured into vassalage without a war: likewise by standing at the heart in prime time. It is captured by the clan whose players stand at the heart alone; if a defender or two different clans are there, progress pauses, and if the capturer changes it starts over. You cannot capture an ally, your own vassal, a clan under newbie protection or with a shield. The bar is visible to the capturer, the defenders and everyone nearby.

Consequences:

- The attacker who reaches the heart captures it by standing next to it and makes the clan its vassal for **14 days**.
- Tribute is deducted automatically into the vassal's virtual treasury, which the suzerain collects by caravan along the vassalage trade route:
    - 20% of resource collector output;
    - 20% of the resources the vassal ships on its trade routes (except the vassalage trade route itself).
- For the attacker this is two weeks of income rather than one-time loot.
- The suzerain collects tribute by caravan from the vassal's territory. When vassalage ends, uncollected tribute returns to the vassal. A new capture replaces the previous suzerain.

### Patronage

- A clan can create a base heart block with built-in vassalage to the creating clan and give it to another clan.
- A crushed clan without resources asks acquaintances or the global chat for such a block and starts over, paying tribute to its patron.
- Currently: such a heart costs 16 emeralds from the patron's upkeep, at most 2 wards; vassalage lasts 14 days.
- Another path to recovery: gather resources from events and depleted mines, sell them to the buyer and set up a new base.

### Raid shield

- After a successful siege the base gets a shield: its blocks don't lose HP for the shield's duration (e.g. 24–48 h). A clan cannot be worn down by constant sieges.

### Anti-griefing

The risk: an enemy doesn't take vassalage but keeps breaking a collector over and over to drive a clan off the server. What stops them:

- A siege requires a war: a public journal entry, growing fatigue and upkeep.
- After a siege the base has a shield.
- A raid is costly for the attacker too: travel on foot, pickaxes, the risk of losing gear on site or on the way; you can only log out safely on your own base.
- The victim can call in other clans: "so-and-so keeps coming here, we'll open the doors for you, take his stuff".

## Alliances and balance of power

The risk scenario: three big clans team up, supply themselves and wipe out everyone else. The answer — logistics makes a big alliance vulnerable, and a siege yields a vassal, not an empty map.

- **An alliance** is mutual: a leader's proposal confirmed by the other clan's leader. Currently it only gives the defender bonus on the ally's territory; you can't siege together — each needs its own war.
- **An alliance needs caravans too.** Resources between member clans and to proxies are hauled by hand. Guarding every caravan and every miner is unrealistic.
- **Small clans strike at logistics:** they intercept caravans and lone players in canyons.
- **A semi-nomadic clan.** If it isn't allowed to set up a permanent base, a clan can live without one and keep cutting supply lines. It's more profitable for an alliance to let it settle and agree on neutrality.
- **A siege is expensive and public:** war declaration, war fatigue, a shield after the raid. Fighting everyone at once is too expensive.
- **Resources are scattered across the map:** each key resource is in 2–3 regions at different ends of the map, and the farther the region, the longer and more vulnerable the route.

## Mining and building

All wilderness blocks have HP too — the same system as on bases, just with small base values. This makes resources valuable, even wood, dirt and cobblestone.

- **A hit removes HP by tool:** hand — 1, wooden tool — 2, stone — 3, iron — 5 and so on (numbers are preliminary).
- **Base HP by block type:** dirt and sand lower, wood medium, stone and ore higher. Guideline from discussion: about 100 hits by hand.
- **Tool wear** is increased, as on bases.
- **The pure-cost variant** (the block breaks immediately but wears the tool heavily) was rejected: wood and dirt can be broken by hand, and houses would become free.

### Building

- In the wilderness placing a block takes time: hold right-click for ~4 s. Walling yourself in within 5 minutes is no longer possible.
- Inside your own base it's faster: resources stay valuable, but building a beautiful town isn't a chore. Currently on your own active base a block is placed instantly, in the wilderness it takes 4 s. Only solid blocks are slowed: torches, ladders, plants and buckets are placed instantly.
- **A foreign base.** On another clan's protected (upkeep paid) territory, including an ally's, outsiders cannot place blocks or empty buckets at all. Exceptions: a plot the player was invited to, and an active war between the player's clan and the owner clan — then building is allowed but slower than in the wilderness (8 s per solid block): raiders can place a ladder or block a passage, but not build fortifications. An unpaid base counts as wilderness.
- **Redstone on a foreign base is always forbidden**, even during a war and on an unpaid base: dust, redstone blocks and torches, repeaters, comparators, observers, pistons, dispensers, buttons, levers, pressure plates, tripwire hooks, targets, lamps, sensors, TNT, slime and honey, special rails, hoppers. Otherwise pistons and mechanisms could bypass walls with HP.
- Who builds on your own base — see «Clans: roles and tags» (leaders, «Builders», plot residents).

## Map and resources

The map is bounded, pre-generated and hand-designed: resources are laid out by region so that clans trade and meet on predictable routes.

- **Size:** crossable from edge to edge, as in the original Minto. Mines and canyons are pre-generated, player movement is planned in advance.
- **Producer regions:** desert — cactus and sand, plains — wool and livestock, mountains — iron and stone, forest — wood. A clan chooses its base location for the resources it needs.
- **Several sources per resource:** every resource exists in at least 2–3 regions so that no single clan can monopolise it.
- **Canyons and mines:** shared mining spots. Hearts and nodes can't be placed there; ordinary blocks can, but without protection. On a schedule the server adds ore to their walls. Mining there is easier than digging your own mine, so players meet there.
- **Fertile zones:** normal plant growth and animal breeding speed only there. Outside them growth is heavily slowed, and food has to be grown or bought.
- **Resource collector:** the leader takes a collector barrel in the «Clan collectors» menu (or /clan collector): a clan's first collector is free (once ever), each next one costs emeralds from the inventory (32 by default); it is placed on the base and a structure is built around it according to a blueprint. The collector accumulates the region's resources (including iron, emeralds, ores) up to a limit. They need to be collected regularly, e.g. once a day. The more collectors a clan has, the lower each one's output.

## Economy

Emeralds are the upkeep currency. They come from trade, collectors, events and the buyer, and are spent on upkeep, building bases and losses on death.

### Upkeep and energy

- **Upkeep runs out:** the territory stops protecting blocks (base HP) and giving the defender bonus, and the heart stops accumulating energy. The spheres stay in place; the clan builds and breaks as usual. Upkeep is charged every minute.
- Emeralds sit in the base heart treasury — a chest window in the heart menu — and are spent gradually. Only the leader and treasurers can open the treasury, and only when the menu was opened by clicking the heart itself (not from the compass). Each node and base level adds its own cost.
- The heart accumulates energy. It can be taken out as energy blocks and poured into a chunk.
- Chunk energy is spent on every growth tick of plants and animals and recovers slowly. A single farm can't pay for upkeep.
- Energy generation grows with base size, member count, building prestige and participation in activities. Currently: 20 per hour + 5 per member + 10 per node + 1 per prestige point, up to 2000 in the heart. One cell = 100 energy.
- **Fertile zones** multiply a chunk's natural energy maximum and recovery.

### Heart removal and abandoned bases

- **Heart removal timer.** For a base without upkeep a 7-day timer can be started in the heart menu (upkeep.heart-removal-days). It can be started by clan members or by the clan's suzerain (in the vassal's heart menu, right-click on it). When it runs out, the heart and nodes disappear (blocks included) and the territory becomes wilderness; proxies — being separate bases — remain. The timer is cancelled by the clan that started it or by paying upkeep.
- **Abandoned base.** If the last member leaves a clan with a base, the clan is not disbanded: the base stays without members and the removal timer starts automatically. As soon as the heart disappears, the clan is disbanded.
- **Taking over an abandoned base.** Right-clicking its heart opens a menu. A player without a clan can press a button (with confirmation) to become the leader of that clan: the heart, nodes, treasury and buildings become theirs and the timer is removed. A player in another clan sees that taking over the base is only possible after leaving their current clan.
- The timer state is shown above the heart (a label), in clan lists in menus, in the right-side panel on the base's territory and on the website.

### Universal buyer

- Buys any item for emeralds. This is the price floor: even if nobody buys your wool, you can turn it into upkeep.
- The buyer's price is noticeably worse than the price between players, otherwise trade between clans would lose its point.
- The buyer is a stationary villager: a selling window, fractional remainders accumulate on the player's account, damaged items are cheaper. Doesn't take emeralds, shulkers and bundles with items, or plugin items.

### Death

- Only part of your items drop, e.g. half. The rest is destroyed — an emerald and resource sink against inflation.

### Events

- **In the Nether:** structures appear periodically; a player starts one and fights off waves of mobs. In Hearthold the reward is weakened or tied to resources so that events aren't a shortcut to the top.
- **In the overworld:** structure locations, their status and timer are visible to everyone. When the timer ends, the chests open and the fight for them begins.

A villager with custom trades may appear in temporary chest structures on a timer — another reason to come to the event.

Currently: event sites (core and radius) are placed by an admin on pre-built structures. **Waves** — right-click the core, several waves in a row; failure if a wave isn't cleared in time or no players are nearby; reward at the core, cooldown. **Chests** — an announcement, half an hour later containers within the radius are filled with loot and stay open for a limited time; the rest of the time they are locked and indestructible; a trader may appear by chance.

## Weight and logistics

Resources have weight, so they have to be physically transported: you can't make one trip to a mine "for life" or bring a proxy in a single inventory.

- **Overload is tiered:** above a threshold — slowdown; further — stronger slowdown and no sprinting; at the maximum the player can't move.
- **Guideline:** a full block weighs 1, a player carries about 10 without penalty. Weight is better counted in cargo units by item category.
- **Transport raises the limit:** a horse, a wagon, a minecart on rails. Horses, rails, gold and redstone become goods.
- **Carrying capacity potions** — custom, a niche for brewers.
- **Ender chests and shulkers** are removed or restricted: only gear and consumables can go in them, not resources.

Typical clan setups: a guarded chest at the mine, or a conveyor — part of the group mines, part hauls to the rear. All of this creates routes where you can meet outsiders.

## Trade routes

Trade is an agreement in the interface plus manual delivery. For breaking an agreement a clan loses reputation; for losing a caravan — only the cargo itself.

### How it works

1. Two clans agree in the interface: what, how much and how often. Example: 64 wool for 64 wood every 3 days.
2. A clan's trusted member sees the list of pending trade route deliveries, e.g. "1: 64 wool".
3. They choose «Equip trade route». From the trade treasury (a virtual store "material → amount": any member can deposit on their own base, the leader and trusted members can withdraw; enchanted, named and damaged items are not accepted) the route gets as much of the resource as the weight limit allows, but no more than what remains on the agreement.
4. You can take a horse or wagon from the treasury — the limit is higher, so more leaves in one trip.
5. The player hauls the cargo by hand — in the inventory, on a horse or a wagon — to the other clan's zone and completes the delivery (below).

### Trade route book

The agreement is a book. A member of either of the two clans can open it. Inside is a schedule: period, cargo, direction, status. A two-way exchange goes row by row in both directions.

Example of a two-way route:

| Period | Cargo | Direction | Status |
| --- | --- | --- | --- |
| 1.11–3.11 | 16 wool | Snipers → Hamsters | Fulfilled |
| 1.11–3.11 | 32 food | Hamsters → Snipers | Fulfilled |
| 4.11–6.11 | 16 wool | Snipers → Hamsters | Overdue |
| 4.11–6.11 | 32 food | Hamsters → Snipers | Fulfilled |
| 7.11–9.11 | 16 wool | Snipers → Hamsters | Not due yet |
| 7.11–9.11 | 32 food | Hamsters → Snipers | Not due yet |

### Delivery and the suitcase

- **Suitcase.** Cargo is packed into a custom item: its contents are recorded inside, and its weight matches the cargo. It can't be dropped, and only a player of the other clan can open it. This way a courier can't steal from their own clan.
- **Completion.** The courier or wagon enters the receiving town's zone and stands still for 10 seconds; the interface hints that you need to stand still. The cargo automatically goes to the receiver's treasury and the agreement's progress is updated.
- **Cancellation.** By returning to their own base and standing still for 10 seconds, the courier cancels the delivery and the cargo returns to the treasury.
- **Returning horses and wagons.** Transport taken from the treasury must be brought back after unloading, again standing still for 10 seconds.
- **Caravan log.** Clan leaders and admins see when and who equipped caravans and what was taken.

### Horses and wagons in the treasury

- A tamed, saddled horse on the base can be moved into the treasury and later issued for a route.
- A horse is upgraded to a wagon through the interface for resources.
- Implementation idea: vanilla mules and llamas with chests (llamas walk in a chain) can serve as a "wagon" without custom models.

### Breaches and reputation

- **Being overdue doesn't break the agreement automatically.** The affected clan finds out the reason in chat and decides itself: forgive (the clan didn't make it but didn't mean to cheat) or press «Declare terminated with a reason».
- **Termination reasons** are chosen from a list, including «The other side did not fulfil its part». A termination with this reason goes to the global «Failed trade agreements» list and lowers the offender's reputation.
- **The caravan didn't arrive** — the system does nothing. Robbed, turned back, carried back home — that's the supplying clan's business. A new caravan can be equipped.
- Result: caravans can be intercepted, and escorts and allied roads make sense.

## Timed crafting

Cooking, brewing, enchanting and the anvil run on timers. The timer keeps running while the player is offline.

| Process | Duration | Note |
| --- | --- | --- |
| Enchanting | 2 min × level (level 30 = 60 min) | The item sits in the table with a timer above it |
| Anvil | By level cost, like enchanting | Closes the loophole of bypassing the timer through books |
| Brewing | Extended (number not set) | Including carrying capacity potions |
| Cooking | Extended (number not set) | — |

- An unfinished item sits in the station and can become a raid target.
- Parallelism is limited: enchanting tables, anvils and brewing stands work only on your own base and only for your own clan, and their number per clan is limited (currently 2 / 2 / 3). Stations outside bases (in villages etc.) don't work.
- Currently cooking food and brewing take 4 times longer than vanilla, other smelting is vanilla. A timer is shown above the station; a broken station drops the item.

## Plots and prestige

Plots distribute rights inside a base, and building ratings give prestige instead of emeralds.

### Plot heart

- A plot is not a circle but a box (cuboid) with the heart in the centre. The player sets the size of a new plot in advance in the «Plots» menu (along X, Y, Z, stored per player); the next placed heart gets that size.
- A placed plot has its own menu (right-click the plot heart or click the plot in the list): size along each axis with ±1/±5 buttons or a number, name, members, rating, compass, boundary display with particles. Changed by leaders.
- On placement and on any size change the plugin checks that the whole box (its entire surface, not only the corners) lies on clan territory and doesn't touch other plots; otherwise it refuses with the reason and the coordinate.
- Placed inside a base. Clan leaders invite players (one or several) — not only from their own clan. A broken plot heart removes the plot. Currently up to 20 plots per clan, up to 48 blocks per axis.
- In a plot the invited player can build, break and open containers. The plot a player is invited to is their home: in the clan member list they get the «Has a home» badge.
- A plot heart gives no HP — protection comes only from the base and node spheres.

### Building rating

- In the original: a «Rate» button, an algorithm outputs a number, the tier is computed across the server (top 3 — tier 10, top 5% — tier 9 and so on), a daily emerald reward, part of it goes to the clan treasury.
- **In Hearthold** the reward is replaced with prestige: clan level, display in the compass, an energy bonus. With slow building, emeralds for ratings would only go to rich clans.
- Currently the rating is the sum of "material value × √amount" (variety is worth more than filling with a single block), at most once a day. A clan's prestige is the sum of the tiers of its 5 best plots.
- Additionally — seasonal or weekly contests with player voting.

## Newbie protection and proxies

The first base is cheap and invulnerable for 3–7 days. All later bases and proxies are expensive and vulnerable right away.

### First base

- Low cost and 3–7 days of invulnerability: it can't be broken.
- Protection is tied to the account and given once.
- While protection lasts: only radius 16 is protected (the sphere occupies the full radius), nodes are forbidden, the base can have at most 6 containers — so that an invulnerable base doesn't become a warehouse. A protected base can't be besieged and war can't be declared on it. Currently protection lasts 5 days.

### Ending protection early

- The clan adds an experienced player — someone who was already in a clan whose protection expired. Before that, a popup: "Are you sure you want to add an experienced player? Your base's newbie protection will end early".
- The clan places a proxy (a second base).
- The clan declares war (foreign bases can only be broken during a war, so "breaks other people's blocks" = "declared war").
- All members of a clan whose protection ended, and everyone who joins a clan with an unprotected base, become experienced.

### Proxies and new bases

In the previous season a proxy cost the emeralds from one event and was set up in 5–10 minutes. In Hearthold you need to:

1. Bring the resources, accounting for weight — several trips.
2. Pay for creating the base and the first upkeep.
3. Build a collector according to the blueprint.
4. Build minimal protection around the heart. All this time the base is open to attack.

## Bodies and the compass

A player who logs out stays in the world as a body, and the compass finds unprotected bodies — so you can't survive alone outside a base.

- The body lies down (sleeping pose). To make it easy to hit, a hit counts along the whole length of the body, not just the feet (an invisible 1×0.6 hitbox along the body).
- An offline player's body can be killed and looted (with the usual partial loss on death). Only a player can kill it: mobs, lava and falling don't affect it. If the body was killed, on login the player appears at their respawn point without inventory or experience.
- A body on its own clan's base counts as protected: the compass doesn't show it, but it can still be reached and killed.
- The compass finds the nearest unprotected body, asleep or not. Digging in won't help.
- The compass shows the list of clans: path, distance, number of players, level and (in Hearthold) reputation.
- The Hearthold compass is given to everyone on login and after death (it doesn't drop), and with the /compass command. Right-click opens a menu: clan guide, compass to base, your clan's menu, clans, nearest body, respawn point, cargo weight, events, website; for operators — the admin menu.
- After joining a clan the compass points to the base heart and a guide book opens: why you should run to the base and what's there.
- Event messages in chat have a «Set compass» button for that event.

## Web portal and map

An interactive map on the website makes the political situation public: who lives where, how well protected they are and who is on the road right now.

- **Bases** — heart and node radii.
- **Current events** — structures, their status and timers.
- **Players outside their base** — positions of players currently outside their clan's spheres. What exactly is visible depends on the clan's scouting level.

### Access and scouting

The map is available only after logging in. The level of visibility depends on how much the clan sponsors the «Scouting» service: each level raises upkeep.

| Scouting level | Players outside bases | Couriers with cargo | Delay |
| --- | --- | --- | --- |
| No scouting | Not visible | Not visible | — |
| 1 | Visible | Not distinguished from players | 1 min |
| 2 | Visible | Marked separately | 1 min |
| 3 | Visible | Marked separately | 5 s |

Bases, radii and events are presumably visible to all logged-in players.

The leader changes the scouting level with /site scouting or in the compass menu «Clan scouting», which shows the current level and how much it adds to upkeep. A raised level holds for at least a day: it can't be lowered earlier, so "turn it on, look and turn it off" won't work.

Open questions: are players visible in caves and in the Nether; the upkeep cost of each level.

### Politics and trade

The political and trade summary lives on the website rather than inside Minecraft. Politics is open to all logged-in players:

- the war journal: who is at war with whom, since when, each clan's war fatigue;
- vassalages and their terms;
- trade routes, their schedules and statuses — only for your own clan;
- the list of failed trade agreements and clan reputation; for each clan in the list — whether its base is abandoned and when the heart will be removed;
- for clan leaders — their clan's caravan log.

### Prime time on the website

Current prime-time status and the weekly schedule. If an administrator turned prime time on or off manually, this is shown separately from the schedule.

### Admin panel

Visible only to server operators:

- block-breaking economy: for common wilderness blocks and each base level (in and out of prime time) — hits, time, how many tools a block costs and how many blocks a tool of each tier survives;
- regeneration: block recovery time by base level, accounting for war fatigue;
- energy: a chunk's maximum and recovery, how many plants and animals will grow, fertile zones, how much energy clans of different sizes generate;
- collectors: the penalty for their number;
- clans: treasury, spending, fatigue, reputation, trade treasury, routes, wars.

### Website login

1. The player enters a command in-game.
2. A clickable link with a one-time token appears in chat.
3. Through the link the website registers or logs in the player, binding the session to their game account and clan.

## Clans: roles and tags

- Roles: leader, trusted (equips caravans, trade treasury), member.
- The last member of a clan with a base can leave: the clan remains abandoned (see «Heart removal and abandoned bases»). A clan without a base is disbanded when its last member leaves.
- Tags are permissions beyond the role, given by the leader in the heart menu («My clan» in the compass): the «Members and tags» section, click a member's head to give or remove tags and the «Trusted» role. Tags: **Treasurer** — the heart treasury; **Quartermaster** — takes resources from collector barrels; **Builder** — builds and breaks across the whole clan territory (including plots and proxy bases), places nodes and proxies. The leader has all permissions.
- **Has a home** is a badge, not an assignable tag: it appears automatically when a player is invited to a plot (the home is that plot).
- **Building rights:** on your own base only leaders and «Builders» build and break (including buckets); other members — only in plots they were invited to. Outsiders break by siege rules and place blocks only during a war with the owner clan (see «Building»).
- **Nodes and proxies** are placed by leaders and «Builders»; bought and removed only by leaders (your own proxy can be broken only by the leader and only if no nodes would be cut off without it).
- **Chests on a base** (any containers except collector barrels) have an access setting: all players on the server, the whole clan, plot members (for chests in a plot) or only selected tags. Leaders can always open everything. Defaults: in a plot — plot members and leaders; outside plots — the whole clan. Set by the leader: Shift+right-click a container with an empty hand. Outsiders can't open chests that are not accessible to them even during a war: in a raid a chest can only be broken (by HP, like any base block), and its contents drop.
- Tags are visible to clanmates in menus, in the right-side panel and above heads.

## Interface

- **Language.** All messages, menus and the website are translated into English (default) and Russian; the server language is set in config.yml (`language: en|ru`), and texts can be overridden with files in plugins/Hearthold/lang/<language>/.
- **Everything available as a command is also in a menu.** Text (clan name, plot name, trade route cargo, an amount) is typed into chat after pressing a button; nobody else sees that message, and «cancel» cancels the input. Dangerous actions (leaving the clan, war, termination, removal) require confirmation.
- **Each menu lives in one place, without duplicates.** Compass: guide, compass to base, «My clan», clans, nearest body, respawn point, weight, scouting, events, website. Everything about your own clan is inside «My clan» (the heart menu), and clan management is the «Clan management» button there.
- **«My clan»:** without a clan it opens «You are not in a clan» (create a clan, accept an invitation, server clans). In a clan — the heart menu (below), and from it «Clan management»: info, base heart, diplomacy, leaving, plus «War and vassals» (status, journal, vassalages, tribute, patronage heart, the clan's wars); «Trade» (propose a route with a chat wizard, offers to you — accept/decline, routes — book, caravan, forgiving an overdue delivery, termination with a reason; pending deliveries, treasury, stable, reputation, log, failed agreements); «Plots» (plot heart, size of a new plot, top, prestige, plot list; plot menu — size per axis, name, members, rating, compass, boundaries); «Events» (list with compass).
- **Clan card** (from diplomacy or war): info, reputation, alliance or ending it, war, peace or retreat, proposing a trade route.
- **Admin menu:** events — add (type, name and radius typed in), start, compass, remove; any clan's upkeep by any amount.
- **Base heart menu** (right-click the heart or «My clan» in the compass): summary (upkeep, protection, energy out of maximum and income, base level), treasury, energy cell, base level and upgrading it, inviting online players (with a hint: newbie or not, which clan), members with roles and tags, clan collectors (status, resource, penalty, compass to the collector) and the collector blueprint. Members are a separate «Members and tags» section with a list of tags; the leader gives tags by clicking a member's head.
- **The right-side panel** has three sections: «Player» (weight, durability of the tool in hand, clan and tags), «Place» (whose territory and base level; on a foreign base — your clan's relation with it: war, alliance or neutrality, vassal/suzerain, whether there is a trade route; whether the base is abandoned and when the heart will be removed; plot, prime time), «Chunk» (coordinates, energy, recovery, what a collector would yield).
- **Resource collector:** a layer-by-layer blueprint in chat; right-clicking the barrel highlights the missing blocks of the structure.
- **Admin menu** (/hearthold or from the compass): all /hearthold commands as buttons, events, chunk energy and resources, a kit for the collector structure, your own base level, a «Testing» tab (bone meal, a mini-farm with various crops, fast-forwarding collectors by an hour for your own or a selected clan).

## Future

- **Crafting the carrying capacity potion.** Come up with a recipe: currently it's temporarily an awkward potion + iron ingot.
- **AI caravans.** Slow, shown on the map, take a commission. Available only to a clan that has never declared war.
