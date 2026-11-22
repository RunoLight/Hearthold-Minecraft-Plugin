package dev.hearthold.web;

import dev.hearthold.base.Sphere;
import dev.hearthold.base.TerritoryService;
import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanService;
import dev.hearthold.config.Area;
import dev.hearthold.config.MapConfig;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.event.EventService;
import dev.hearthold.i18n.Lang;
import dev.hearthold.plot.PlotService;
import dev.hearthold.trade.TradeRoute;
import dev.hearthold.trade.TradeService;
import dev.hearthold.war.VassalService;
import dev.hearthold.war.War;
import dev.hearthold.war.WarService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Website data. Everything is collected on the server main thread (every 5 s, politics every 30 s) into immutable
 * snapshots; HTTP threads only read prepared snapshots and never touch the world.
 * <p>
 * Player positions are kept as frames for roughly the last minute, so they can be served with the scouting delay.
 */
public final class WebData {

    /** A player in a position frame. */
    record Pos(String name, UUID clan, double x, double z, boolean outsideBase, boolean courier) { }

    record Frame(long at, List<Pos> players) { }

    /** Immutable snapshot of the world and politics. */
    record Snapshot(long at, Map<String, Object> world, List<Object> bases, List<Object> zones, List<Object> regions,
                    List<Object> events, Map<UUID, UUID> membership, Map<UUID, String> clanNames,
                    Map<UUID, Integer> scouting, Map<String, Object> politics, Map<UUID, List<Object>> routes) { }

    private final ClanService clans;
    private final TerritoryService territory;
    private final EventService events;
    private final WarService wars;
    private final VassalService vassals;
    private final TradeService trade;
    private final PlotService plots;
    private final ScoutingService scouting;
    private final Predicate<ItemStack> isSuitcase;
    private final Supplier<HeartholdConfig> config;
    private final dev.hearthold.health.PrimeTimeClock primeTime;

    private volatile Snapshot snapshot;
    private volatile Map<String, Object> politics = Map.of();
    private volatile Map<UUID, List<Object>> routes = Map.of();
    private final Deque<Frame> frames = new ConcurrentLinkedDeque<>();
    private int ticks;

    /** Special state of a clan base (abandoned, heart being removed) or null. */
    private java.util.function.Function<UUID, String> baseNotice = id -> null;

    public void setBaseNotice(java.util.function.Function<UUID, String> baseNotice) {
        this.baseNotice = baseNotice;
    }

    public WebData(ClanService clans, TerritoryService territory, EventService events, WarService wars,
                   VassalService vassals, TradeService trade, PlotService plots, ScoutingService scouting,
                   Predicate<ItemStack> isSuitcase, Supplier<HeartholdConfig> config,
                   dev.hearthold.health.PrimeTimeClock primeTime) {
        this.primeTime = primeTime;
        this.clans = clans;
        this.territory = territory;
        this.events = events;
        this.wars = wars;
        this.vassals = vassals;
        this.trade = trade;
        this.plots = plots;
        this.scouting = scouting;
        this.isSuitcase = isSuitcase;
        this.config = config;
    }

    Snapshot snapshot() {
        return snapshot;
    }

    /** Latest position frame not newer than {@code now - delaySeconds}; null if there is none yet. */
    Frame frameAt(long time) {
        Frame best = null;
        for (Frame f : frames) {
            if (f.at() <= time && (best == null || f.at() > best.at())) {
                best = f;
            }
        }
        return best;
    }

    Frame latest() {
        return frames.peekLast();
    }

    /** Called every 5 seconds on the main thread. */
    public void refresh() {
        long now = System.currentTimeMillis();
        HeartholdConfig c = config.get();
        if (ticks++ % 6 == 0) {
            politics = buildPolitics();
            routes = buildRoutes();
        }
        String worldName = c.web().world();
        World world = Bukkit.getWorld(worldName);

        Map<String, Object> worldInfo = new LinkedHashMap<>();
        worldInfo.put("name", worldName);
        if (world != null) {
            WorldBorder b = world.getWorldBorder();
            double half = Math.min(b.getSize(), 60_000) / 2;
            worldInfo.put("minX", b.getCenter().getX() - half);
            worldInfo.put("maxX", b.getCenter().getX() + half);
            worldInfo.put("minZ", b.getCenter().getZ() - half);
            worldInfo.put("maxZ", b.getCenter().getZ() + half);
        }

        worldInfo.put("prime", primeInfo(c, now));

        Map<UUID, String> names = new HashMap<>();
        Map<UUID, UUID> membership = new HashMap<>();
        for (Clan clan : clans.all()) {
            names.put(clan.id(), clan.name());
            clan.members().keySet().forEach(p -> membership.put(p, clan.id()));
        }

        List<Object> bases = new ArrayList<>();
        for (Sphere s : territory.spheres()) {
            if (!s.world().equals(worldName)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("clan", names.getOrDefault(s.clanId(), "?"));
            m.put("kind", s.kind().name());
            m.put("x", s.x());
            m.put("z", s.z());
            m.put("radius", s.radius());
            m.put("active", s.isActive(now));
            bases.add(m);
        }

        List<Object> zones = new ArrayList<>();
        for (Area a : c.neutralZones()) {
            if (a.world().equals(worldName)) {
                zones.add(box(a, "neutral", null));
            }
        }
        for (HeartholdConfig.FertileZone f : c.energy().fertileZones()) {
            if (f.area().world().equals(worldName)) {
                zones.add(box(f.area(), "fertile", null));
            }
        }
        List<Object> regions = new ArrayList<>();
        for (MapConfig.Region r : c.map().regions()) {
            if (r.area().world().equals(worldName)) {
                List<String> res = r.perHour().keySet().stream().map(m -> m.name().toLowerCase(Locale.ROOT)).toList();
                regions.add(box(r.area(), "region", res));
            }
        }

        List<Object> eventList = new ArrayList<>();
        for (EventService.Site s : events.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name());
            m.put("type", s.type().name());
            m.put("state", s.state().title());
            m.put("world", s.world());
            m.put("x", s.x());
            m.put("z", s.z());
            m.put("nextAt", s.nextAt());
            eventList.add(m);
        }

        // Position frame for players in the main world.
        List<Pos> players = new ArrayList<>();
        if (world != null) {
            for (Player p : world.getPlayers()) {
                if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) {
                    continue;
                }
                UUID clan = membership.get(p.getUniqueId());
                UUID claim = territory.claimAt(worldName, p.getLocation().getBlockX(), p.getLocation().getBlockY(),
                        p.getLocation().getBlockZ());
                boolean outside = clan == null || !clan.equals(claim);
                players.add(new Pos(p.getName(), clan, p.getLocation().getX(), p.getLocation().getZ(), outside,
                        isCourier(p)));
            }
        }
        frames.addLast(new Frame(now, List.copyOf(players)));
        long keep = (c.web().slowDelaySeconds() + 20) * 1000L;
        while (!frames.isEmpty() && frames.peekFirst().at() < now - keep) {
            frames.pollFirst();
        }

        snapshot = new Snapshot(now, worldInfo, List.copyOf(bases), List.copyOf(zones), List.copyOf(regions),
                List.copyOf(eventList), Map.copyOf(membership), Map.copyOf(names), scouting.snapshot(),
                politics, routes);
    }

    private boolean isCourier(Player p) {
        for (ItemStack i : p.getInventory().getContents()) {
            if (i != null && isSuitcase.test(i)) {
                return true;
            }
        }
        if (p.getVehicle() instanceof AbstractHorse horse) {
            for (ItemStack i : horse.getInventory().getContents()) {
                if (i != null && isSuitcase.test(i)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, Object> box(Area a, String type, List<String> resources) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("name", a.name());
        m.put("minX", a.minX());
        m.put("maxX", a.maxX());
        m.put("minZ", a.minZ());
        m.put("maxZ", a.maxZ());
        if (resources != null) {
            m.put("resources", resources);
        }
        return m;
    }

    /**
     * Prime time: current status (an admin may force it on or off regardless of the schedule),
     * scheduled status and the windows for the coming week in milliseconds.
     */
    private Map<String, Object> primeInfo(HeartholdConfig c, long now) {
        dev.hearthold.config.PrimeTime pt = c.primeTime();
        java.time.ZonedDateTime moment = java.time.Instant.ofEpochMilli(now).atZone(pt.zone());
        java.time.LocalDate today = moment.toLocalDate();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", primeTime.isActive());
        m.put("scheduled", pt.isActive(moment));
        Boolean forced = primeTime.forced();
        m.put("forced", forced);
        m.put("zone", pt.zone().getId());
        m.put("dayStart", today.atStartOfDay(pt.zone()).toInstant().toEpochMilli());
        List<Object> days = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            days.add(today.plusDays(i).atStartOfDay(pt.zone()).toInstant().toEpochMilli());
        }
        m.put("days", days);
        List<Object> windows = new ArrayList<>();
        long horizon = today.plusDays(7).atStartOfDay(pt.zone()).toInstant().toEpochMilli();
        Long nextStart = null;
        Long currentEnd = null;
        for (int i = -1; i < 8; i++) {
            java.time.LocalDate d = today.plusDays(i);
            if (!pt.days().contains(d.getDayOfWeek())) {
                continue;
            }
            for (dev.hearthold.config.PrimeTime.Window w : pt.windows()) {
                long from = d.atTime(w.from()).atZone(pt.zone()).toInstant().toEpochMilli();
                java.time.LocalDate endDay = w.to().isAfter(w.from()) ? d : d.plusDays(1);
                long to = endDay.atTime(w.to()).atZone(pt.zone()).toInstant().toEpochMilli();
                if (to > now && from < now) {
                    currentEnd = currentEnd == null ? to : Math.max(currentEnd, to);
                }
                if (from > now && (nextStart == null || from < nextStart)) {
                    nextStart = from;
                }
                if (to > (Long) days.get(0) && from < horizon) {
                    windows.add(List.of(from, to));
                }
            }
        }
        m.put("windows", windows);
        m.put("nextStart", nextStart);
        m.put("currentEnd", currentEnd);
        List<Object> labels = new ArrayList<>();
        pt.windows().forEach(w -> labels.add(w.toString()));
        m.put("windowLabels", labels);
        List<Object> dayNames = new ArrayList<>();
        pt.days().stream().sorted().forEach(d -> dayNames.add(
                d.getDisplayName(java.time.format.TextStyle.SHORT, Locale.forLanguageTag(Lang.language()))));
        m.put("dayLabels", dayNames);
        return m;
    }

    /** Political situation: visible to everyone logged in. */
    private Map<String, Object> buildPolitics() {
        long now = System.currentTimeMillis();
        Map<String, Object> p = new LinkedHashMap<>();
        List<Object> ongoing = new ArrayList<>();
        for (War w : wars.ongoing()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("attacker", w.attackerName());
            m.put("defender", w.defenderName());
            m.put("declaredAt", w.declaredAt());
            m.put("activeAt", w.activeAt());
            m.put("endsAt", w.endsAt());
            m.put("active", w.isActive(now));
            ongoing.add(m);
        }
        p.put("wars", ongoing);
        List<Object> journal = new ArrayList<>();
        for (War w : wars.journal(30)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("attacker", w.attackerName());
            m.put("defender", w.defenderName());
            m.put("declaredAt", w.declaredAt());
            m.put("outcome", w.outcome() == null ? null : w.outcome().title());
            journal.add(m);
        }
        p.put("journal", journal);
        List<Object> vass = new ArrayList<>();
        for (VassalService.Vassalage v : vassals.active()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("vassal", v.vassalName());
            m.put("suzerain", v.suzerainName());
            m.put("kind", Lang.tr(v.kind() == VassalService.Kind.PATRONAGE ? "web.vassal.patronage" : "web.vassal.conquest"));
            m.put("endsAt", v.endsAt());
            vass.add(m);
        }
        p.put("vassalages", vass);
        List<Object> failures = new ArrayList<>();
        for (TradeService.Failure f : trade.failures(30)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("offender", f.offender());
            m.put("victim", f.victim());
            m.put("reason", f.reason());
            m.put("at", f.at());
            failures.add(m);
        }
        p.put("failures", failures);
        List<Object> clanList = new ArrayList<>();
        for (Clan c : clans.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name());
            m.put("members", c.members().size());
            m.put("reputation", trade.reputation(c.id()));
            m.put("prestige", plots.prestige(c.id()));
            m.put("shield", wars.isShielded(c.id()));
            m.put("status", baseNotice.apply(c.id()));
            clanList.add(m);
        }
        p.put("clans", clanList);
        return Map.copyOf(p);
    }

    /** Trade routes of each clan with their schedule; visible only to members of that clan. */
    private Map<UUID, List<Object>> buildRoutes() {
        Map<UUID, List<Object>> result = new HashMap<>();
        for (Clan clan : clans.all()) {
            List<Object> list = new ArrayList<>();
            for (TradeRoute r : trade.routesOf(clan.id())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r.shortId());
                m.put("partner", clans.byId(r.partnerOf(clan.id())).map(Clan::name).orElse("?"));
                m.put("periodDays", r.periodDays());
                m.put("status", r.status() == TradeRoute.Status.ACTIVE ? Lang.tr("web.route.active")
                        : Lang.tr("web.route.terminated", TradeService.Reason.valueOf(r.reason()).title()));
                List<Object> rows = new ArrayList<>();
                int current = r.currentPeriod();
                for (int p = Math.max(0, current - 5); p <= current + 1; p++) {
                    for (TradeRoute.Line l : r.lines()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("from", r.periodStart(p));
                        row.put("to", r.periodStart(p + 1) - 1);
                        row.put("cargo", l.amount() + " " + l.material().name().toLowerCase(Locale.ROOT));
                        row.put("direction", clans.byId(l.fromClan()).map(Clan::name).orElse("?") + " → "
                                + clans.byId(r.partnerOf(l.fromClan())).map(Clan::name).orElse("?"));
                        row.put("status", r.rowStatus(p, l).title());
                        row.put("delivered", r.delivered(p, l.fromClan(), l.material()));
                        rows.add(row);
                    }
                }
                m.put("rows", rows);
                list.add(m);
            }
            result.put(clan.id(), List.copyOf(list));
        }
        return Map.copyOf(result);
    }
}
