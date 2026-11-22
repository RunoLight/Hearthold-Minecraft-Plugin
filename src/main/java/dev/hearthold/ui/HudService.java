package dev.hearthold.ui;

import dev.hearthold.base.TerritoryService;
import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanService;
import dev.hearthold.config.Area;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.config.WeightConfig;
import dev.hearthold.config.MapConfig;
import dev.hearthold.collector.CollectorService;
import dev.hearthold.economy.ChunkEnergyService;
import dev.hearthold.health.PrimeTimeClock;
import dev.hearthold.i18n.Lang;
import dev.hearthold.plot.Plot;
import dev.hearthold.plot.PlotService;
import dev.hearthold.weight.WeightService;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Right-side panel (scoreboard): where the player stands (whose base, neutral zone, wilderness), plot and load weight.
 * Entering another territory or plot shows a short subtitle in the centre of the screen.
 */
public final class HudService implements Listener {

    private static final String[] ENTRIES = {"a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o"};

    private record Place(String key, Component line, Component title) { }

    private final ClanService clans;
    private final TerritoryService territory;
    private final PlotService plots;
    private final WeightService weights;
    private final Supplier<HeartholdConfig> config;
    private final ChunkEnergyService energy;
    private final CollectorService collectors;
    private final PrimeTimeClock primeTime;
    private dev.hearthold.base.BaseLevelService levels;

    public void setLevels(dev.hearthold.base.BaseLevelService levels) {
        this.levels = levels;
    }

    /** Relation of own clan to another: "War · trade active" etc.; (own, other) → line. */
    private java.util.function.BiFunction<UUID, UUID, String> relation = (a, b) -> null;
    /** Special state of a clan base (abandoned, heart being removed) or null. */
    private java.util.function.Function<UUID, String> baseNotice = id -> null;

    public void setRelation(java.util.function.BiFunction<UUID, UUID, String> relation) {
        this.relation = relation;
    }

    public void setBaseNotice(java.util.function.Function<UUID, String> baseNotice) {
        this.baseNotice = baseNotice;
    }

    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Map<UUID, String> lastPlace = new HashMap<>();
    /** For testing: the player sees the panel as if they were in this clan. */
    private final Map<UUID, UUID> viewAs = new HashMap<>();

    /** clanId null — revert to own clan. */
    public void setViewAs(UUID player, UUID clanId) {
        if (clanId == null) {
            viewAs.remove(player);
        } else {
            viewAs.put(player, clanId);
        }
    }

    public Optional<UUID> viewAs(UUID player) {
        return Optional.ofNullable(viewAs.get(player));
    }

    public HudService(ClanService clans, TerritoryService territory, PlotService plots, WeightService weights,
                      Supplier<HeartholdConfig> config, ChunkEnergyService energy, CollectorService collectors,
                      PrimeTimeClock primeTime) {
        this.energy = energy;
        this.collectors = collectors;
        this.primeTime = primeTime;
        this.clans = clans;
        this.territory = territory;
        this.plots = plots;
        this.weights = weights;
        this.config = config;
    }

    /** Called every 10 ticks. */
    public void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            update(p);
        }
    }

    private void update(Player player) {
        Location loc = player.getLocation();
        UUID as = viewAs.get(player.getUniqueId());
        Optional<Clan> viewed = as == null ? Optional.empty() : clans.byId(as);
        if (as != null && viewed.isEmpty()) {
            viewAs.remove(player.getUniqueId());
        }
        Optional<Clan> own = viewed.isPresent() ? viewed : clans.ofPlayer(player.getUniqueId());
        Place place = place(player, loc, own);
        Optional<Plot> plot = plots.at(loc.getBlock());

        // Subtitle when the place changes.
        String key = place.key() + "|" + plot.map(pl -> pl.id().toString()).orElse("");
        String previous = lastPlace.put(player.getUniqueId(), key);
        if (previous != null && !previous.equals(key)) {
            Component sub = plot.isPresent() && !previous.contains(plot.get().id().toString())
                    ? Component.text(Lang.tr("hud.title.plot", plot.get().name()), NamedTextColor.AQUA)
                    : place.title();
            player.showTitle(Title.title(Component.empty(), sub,
                    Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1500), Duration.ofMillis(400))));
        }

        List<Component> lines = new ArrayList<>();
        // Player: weight, tool durability, clan.
        lines.add(header(Lang.tr("hud.header.player")));
        lines.add(weightLine(player));
        Component tool = toolLine(player);
        if (tool != null) {
            lines.add(tool);
        }
        String badges = own.map(c -> c.badges(player.getUniqueId())).orElse("");
        lines.add(text(Lang.tr("hud.clan", own.map(Clan::name).orElse(Lang.tr("hud.clan.none")))
                + (badges.isEmpty() ? "" : " · " + badges),
                NamedTextColor.GRAY));
        // Place: whose territory, plot, prime time.
        lines.add(header(Lang.tr("hud.header.place")));
        lines.add(place.line());
        territory.claimOf(loc.getBlock()).ifPresent(c -> {
            if (own.isPresent() && !own.get().id().equals(c.id())) {
                String rel = relation.apply(own.get().id(), c.id());
                if (rel != null) {
                    NamedTextColor color = rel.startsWith(Lang.tr("misc.relation.war")) ? NamedTextColor.RED
                            : rel.startsWith(Lang.tr("misc.relation.alliance")) ? NamedTextColor.AQUA : NamedTextColor.GRAY;
                    lines.add(text(rel, color));
                }
            }
            String notice = baseNotice.apply(c.id());
            if (notice != null) {
                lines.add(text(notice, NamedTextColor.GOLD));
            }
        });
        plot.ifPresent(pl -> {
            boolean invited = pl.members().contains(player.getUniqueId())
                    || own.map(c -> c.id().equals(pl.clanId()) && c.hasAccess(player.getUniqueId(), Clan.Tag.BUILDER)).orElse(false);
            lines.add(text(Lang.tr("hud.plot", pl.name()) + (invited ? "" : Lang.tr("hud.plot.foreign")),
                    invited ? NamedTextColor.AQUA : NamedTextColor.RED));
        });
        lines.add(primeLine());
        // Chunk: energy and resources for a collector.
        lines.add(header(Lang.tr("hud.header.chunk")));
        chunkLines(loc, own, lines);
        render(player, lines);
        syncNameTags(player, own);
    }

    /** Durability of the tool in hand. */
    private static Component toolLine(Player player) {
        org.bukkit.inventory.ItemStack item = player.getInventory().getItemInMainHand();
        int max = item.getType().getMaxDurability();
        if (item.isEmpty() || max <= 0 || !(item.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable d)) {
            return null;
        }
        if (d.isUnbreakable()) {
            return text(Lang.tr("hud.durability.unbreakable"), NamedTextColor.AQUA);
        }
        int left = max - d.getDamage();
        double ratio = left / (double) max;
        NamedTextColor color = ratio > 0.5 ? NamedTextColor.GREEN : ratio > 0.2 ? NamedTextColor.YELLOW : NamedTextColor.RED;
        return text(Lang.tr("hud.durability", left, max), color);
    }

    /**
     * Above heads and in the tab list, own clan members show their tags: [Leader · Treasurer]. Teams live
     * on each player's personal scoreboard, so other clans do not see the tags.
     */
    private void syncNameTags(Player viewer, Optional<Clan> own) {
        Scoreboard board = boards.get(viewer.getUniqueId());
        if (board == null) {
            return;
        }
        java.util.Set<String> wanted = new java.util.HashSet<>();
        if (own.isPresent()) {
            Clan clan = own.get();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!clan.isMember(p.getUniqueId())) {
                    continue;
                }
                String badges = clan.badges(p.getUniqueId());
                String name = "mt" + p.getUniqueId().toString().replace("-", "").substring(0, 12);
                wanted.add(name);
                org.bukkit.scoreboard.Team team = board.getTeam(name);
                if (team == null) {
                    team = board.registerNewTeam(name);
                }
                if (!team.hasEntry(p.getName())) {
                    team.addEntry(p.getName());
                }
                team.color(NamedTextColor.GREEN);
                team.suffix(badges.isEmpty() ? Component.empty()
                        : Component.text(" [" + badges + "]", NamedTextColor.GOLD));
            }
        }
        for (org.bukkit.scoreboard.Team t : board.getTeams()) {
            if (t.getName().startsWith("mt") && !wanted.contains(t.getName())) {
                t.unregister();
            }
        }
    }

    private Place place(Player player, Location loc, Optional<Clan> own) {
        Optional<Clan> claim = territory.claimOf(loc.getBlock());
        if (claim.isPresent()) {
            Clan c = claim.get();
            boolean mine = own.map(o -> o.id().equals(c.id())).orElse(false);
            boolean protectedNow = territory.ownerOf(loc.getBlock()).isPresent();
            String state = (levels == null ? "" : Lang.tr("hud.base.level", levels.of(c.id()).name()))
                    + (protectedNow ? "" : territory.isActivating(loc.getBlock())
                    ? Lang.tr("hud.base.activating") : Lang.tr("hud.base.unprotected"));
            NamedTextColor color = mine ? NamedTextColor.GREEN : NamedTextColor.RED;
            return new Place("clan:" + c.id(),
                    text((mine ? Lang.tr("hud.base.own") : Lang.tr("hud.base.foreign", c.name())) + state, color),
                    Component.text(mine ? Lang.tr("hud.base.own") : Lang.tr("hud.base.foreign-title", c.name()), color));
        }
        HeartholdConfig cfg = config.get();
        for (Area a : cfg.neutralZones()) {
            if (a.contains(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) {
                return new Place("zone:" + a.name(), text(Lang.tr("hud.zone.neutral", a.name()), NamedTextColor.YELLOW),
                        Component.text(Lang.tr("hud.zone.neutral", a.name()), NamedTextColor.YELLOW));
            }
        }
        return new Place("wild", text(Lang.tr("hud.wild"), NamedTextColor.GRAY),
                Component.text(Lang.tr("hud.wild"), NamedTextColor.GRAY));
    }

    private Component primeLine() {
        boolean active = primeTime.isActive();
        String manual = primeTime.forced() == null ? "" : Lang.tr("hud.prime.manual");
        return text(Lang.tr("hud.prime", Lang.tr(active ? "hud.prime.on" : "hud.prime.off"), manual),
                active ? NamedTextColor.RED : NamedTextColor.GRAY);
    }

    /** The chunk, its energy and what a resource collector would yield here. */
    private void chunkLines(Location loc, Optional<Clan> own, List<Component> lines) {
        ChunkEnergyService.ChunkKey key = ChunkEnergyService.ChunkKey.of(loc.getBlock());
        double now = energy.energy(key);
        double natural = energy.naturalMax(key);
        NamedTextColor ec = now >= natural * 0.6 ? NamedTextColor.GREEN
                : now >= natural * 0.25 ? NamedTextColor.YELLOW : NamedTextColor.RED;
        lines.add(text(Lang.tr("hud.chunk.energy", key.cx(), key.cz(),
                String.format(Locale.ROOT, "%.0f", now), String.format(Locale.ROOT, "%.0f", natural)), ec));
        double fert = energy.fertility(key);
        lines.add(text(Lang.tr("hud.chunk.regen", String.format(Locale.ROOT, "%.0f", energy.regenPerHour(key)),
                String.format(Locale.ROOT, "%.0f", energy.cap(key)),
                fert > 1.0001 ? Lang.tr("hud.chunk.fertility", String.format(Locale.ROOT, "%.1f", fert)) : ""),
                NamedTextColor.GRAY));

        HeartholdConfig c = config.get();
        MapConfig.Region region = collectors.regionAt(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockZ());
        if (region == null || region.perHour().isEmpty()) {
            lines.add(text(Lang.tr("hud.collector.nothing"), NamedTextColor.DARK_GRAY));
            return;
        }
        // Yield of the clan's next collector, accounting for diminishing returns.
        int next = own.map(cl -> (int) collectors.countOf(cl.id())).orElse(0) + 1;
        double eff = c.map().collector().efficiencyFor(next);
        lines.add(text(Lang.tr("hud.collector.region", region.name(), eff < 0.999
                ? String.format(Locale.ROOT, " ×%.2f", eff) : ""), NamedTextColor.AQUA));
        List<Map.Entry<org.bukkit.Material, Double>> res = new ArrayList<>(region.perHour().entrySet());
        res.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        int shown = 0;
        for (var e : res) {
            if (shown == 2 && res.size() > 3) {
                lines.add(text(Lang.tr("hud.collector.more", res.size() - 2), NamedTextColor.DARK_AQUA));
                break;
            }
            lines.add(Component.text("  ", NamedTextColor.DARK_AQUA)
                    .append(Component.translatable(e.getKey().translationKey()))
                    .append(Component.text(Lang.tr("hud.collector.rate", String.format(Locale.ROOT, "%.1f", e.getValue() * eff))))
                    .decoration(TextDecoration.ITALIC, false));
            shown++;
        }
    }

    private static Component header(String title) {
        return Component.text("▍" + title, NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false);
    }

    private Component weightLine(Player player) {
        WeightConfig w = config.get().weight();
        if (!w.enabled()) {
            return text(Lang.tr("hud.weight.off"), NamedTextColor.GRAY);
        }
        double load = weights.weight(player);
        double cap = weights.capacity(player);
        Entity vehicle = player.getVehicle();
        if (vehicle != null && w.mountCapacity().containsKey(vehicle.getType())) {
            load += weights.cargo(vehicle);
            cap += w.mountCapacity().get(vehicle.getType());
        }
        NamedTextColor color = switch (w.tier(load, cap)) {
            case NONE -> NamedTextColor.GREEN;
            case SLOW -> NamedTextColor.YELLOW;
            case HEAVY -> NamedTextColor.GOLD;
            case STOP -> NamedTextColor.RED;
        };
        return text(Lang.tr("hud.weight", String.format(Locale.ROOT, "%.1f", load), String.format(Locale.ROOT, "%.0f", cap)), color);
    }

    private void render(Player player, List<Component> lines) {
        Scoreboard board = boards.computeIfAbsent(player.getUniqueId(), id -> {
            Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective o = sb.registerNewObjective("hearthold", Criteria.DUMMY,
                    Component.text("Hearthold", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true));
            o.setDisplaySlot(DisplaySlot.SIDEBAR);
            o.numberFormat(NumberFormat.blank());
            return sb;
        });
        if (player.getScoreboard() != board) {
            player.setScoreboard(board);
        }
        Objective o = board.getObjective("hearthold");
        for (int i = 0; i < ENTRIES.length; i++) {
            if (i < lines.size()) {
                var score = o.getScore(ENTRIES[i]);
                score.setScore(ENTRIES.length - i);
                score.customName(lines.get(i));
            } else {
                board.resetScores(ENTRIES[i]);
            }
        }
    }

    private static Component text(String s, NamedTextColor color) {
        return Component.text(s, color).decoration(TextDecoration.ITALIC, false);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        boards.remove(event.getPlayer().getUniqueId());
        lastPlace.remove(event.getPlayer().getUniqueId());
        viewAs.remove(event.getPlayer().getUniqueId());
    }
}
