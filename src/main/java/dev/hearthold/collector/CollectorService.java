package dev.hearthold.collector;

import dev.hearthold.base.Sphere;
import dev.hearthold.base.TerritoryService;
import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanException;
import dev.hearthold.clan.ClanService;
import dev.hearthold.config.MapConfig;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.economy.EconomyService;
import dev.hearthold.i18n.Lang;
import dev.hearthold.storage.Database;
import org.bukkit.Material;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resource collectors. Every few minutes each working collector receives its producing region's resources
 * in proportion to the elapsed time. Each collector's output drops as the clan's collector count grows.
 * Resources pile up in the core barrel up to a limit, so they must be collected regularly.
 * <p>
 * A collector works only if it stands in a producing region, the clan's base heart is active, upkeep is paid
 * and the structure around the core is intact.
 */
public final class CollectorService {

    private final Database db;
    private final ClanService clans;
    private final TerritoryService territory;
    private final EconomyService economy;
    private final Supplier<HeartholdConfig> config;
    private final Logger log;
    private final Map<UUID, Collector> collectors = new HashMap<>();
    private long lastTickAt = System.currentTimeMillis();
    /** Vassal tribute: (clan, material, output) → amount diverted to tribute. */
    private Tribute tribute = (clan, m, amount) -> 0;

    /** Where tribute from a vassal's collectors goes. */
    public interface Tribute {
        double divert(UUID clanId, Material m, double amount);
    }

    public void setTribute(Tribute tribute) {
        this.tribute = tribute;
    }

    public CollectorService(Database db, ClanService clans, TerritoryService territory, EconomyService economy,
                            Supplier<HeartholdConfig> config, Logger log) {
        this.db = db;
        this.clans = clans;
        this.territory = territory;
        this.economy = economy;
        this.config = config;
        this.log = log;
        clans.onDisband(clan -> {
            collectors.values().removeIf(c -> c.clanId().equals(clan.id()));
            issued.remove(clan.id());
        });
    }

    public void loadAll() {
        Connection conn = db.connection();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, clan_id, world, x, y, z, placed_at FROM collectors");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Collector c = new Collector(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                        rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getLong(7));
                collectors.put(c.id(), c);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not load collectors", e);
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT collector_id, material, amount FROM collector_buffer");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Collector c = collectors.get(UUID.fromString(rs.getString(1)));
                Material m = Material.matchMaterial(rs.getString(2));
                if (c != null && m != null) {
                    c.buffer.put(m, rs.getDouble(3));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not load collector buffers", e);
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT clan_id, issued FROM clan_collectors_issued");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                issued.put(UUID.fromString(rs.getString(1)), rs.getInt(2));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not load issued collector counts", e);
        }
    }

    private ChunkResourceService chunkResources;

    public void setChunkResources(ChunkResourceService chunkResources) {
        this.chunkResources = chunkResources;
    }

    /** What a collector produces at a point: the admin chunk assignment, otherwise the config region. */
    public MapConfig.Region regionAt(String world, int x, int z) {
        MapConfig map = config.get().map();
        return chunkResources == null ? map.regionAt(world, x, z) : chunkResources.regionAt(map, world, x, z);
    }

    /** Whether the player may take resources from the collector: leader or quartermaster of the owning clan. */
    public boolean canLoot(Collector col, java.util.UUID player) {
        return clans.byId(col.clanId()).map(c -> c.hasAccess(player, dev.hearthold.clan.Clan.Tag.QUARTERMASTER))
                .orElse(false);
    }

    public Collection<Collector> all() {
        return Collections.unmodifiableCollection(collectors.values());
    }

    public Optional<Collector> at(Block block) {
        return collectors.values().stream().filter(c -> c.isAt(block)).findFirst();
    }

    /** How many collectors each clan has been issued in total. */
    private final Map<UUID, Integer> issued = new HashMap<>();

    /** The clan's next collector is free: it has never been issued one and has none placed. */
    public boolean nextIsFree(UUID clanId) {
        return issued.getOrDefault(clanId, 0) == 0 && countOf(clanId) == 0;
    }

    /** Price of the clan's next collector in emeralds. */
    public double nextCost(UUID clanId) {
        return nextIsFree(clanId) ? 0 : config.get().map().collector().cost();
    }

    public void markIssued(UUID clanId) {
        int n = issued.merge(clanId, 1, Integer::sum);
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT OR REPLACE INTO clan_collectors_issued (clan_id, issued) VALUES (?, ?)")) {
            ps.setString(1, clanId.toString());
            ps.setInt(2, n);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(java.util.logging.Level.WARNING, "Could not save issued collector count", e);
        }
    }

    public long countOf(UUID clanId) {
        return collectors.values().stream().filter(c -> c.clanId().equals(clanId)).count();
    }

    public Collector place(Clan clan, Block block) throws ClanException {
        HeartholdConfig c = config.get();
        MapConfig.CollectorSettings s = c.map().collector();
        if (countOf(clan.id()) >= s.maxPerClan()) {
            throw new ClanException(Lang.tr("collector.error.max", s.maxPerClan()));
        }
        boolean onOwnLand = territory.spheresOf(clan.id()).stream()
                .anyMatch(sp -> sp.covers(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
        if (!onOwnLand) {
            throw new ClanException(Lang.tr("collector.error.own-territory"));
        }
        if (regionAt(block.getWorld().getName(), block.getX(), block.getZ()) == null) {
            throw new ClanException(Lang.tr("collector.error.no-region"));
        }
        Collector col = new Collector(UUID.randomUUID(), clan.id(), block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), System.currentTimeMillis());
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT INTO collectors (id, clan_id, world, x, y, z, placed_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, col.id().toString());
            ps.setString(2, col.clanId().toString());
            ps.setString(3, col.world());
            ps.setInt(4, col.x());
            ps.setInt(5, col.y());
            ps.setInt(6, col.z());
            ps.setLong(7, col.placedAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ClanException(Lang.tr("collector.error.save"));
        }
        collectors.put(col.id(), col);
        col.structureOk = missingParts(block).isEmpty();
        return col;
    }

    public void remove(Collector col) {
        collectors.remove(col.id());
        try (PreparedStatement ps = db.connection().prepareStatement("DELETE FROM collectors WHERE id = ?")) {
            ps.setString(1, col.id().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Could not delete collector", e);
        }
    }

    /** Descriptions of structure parts missing around the core. An empty list means the structure is intact. */
    public List<String> missingParts(Block core) {
        List<String> missing = new ArrayList<>();
        if (core.getType() != Material.BARREL) {
            missing.add(Lang.tr("collector.missing.core"));
        }
        for (MapConfig.StructurePart p : config.get().map().collector().structure()) {
            int bad = 0;
            for (int dx = p.x1(); dx <= p.x2(); dx++) {
                for (int dy = p.y1(); dy <= p.y2(); dy++) {
                    for (int dz = p.z1(); dz <= p.z2(); dz++) {
                        if (!p.blocks().contains(core.getRelative(dx, dy, dz).getType())) {
                            bad++;
                        }
                    }
                }
            }
            if (bad > 0) {
                missing.add(Lang.tr("collector.missing.zone", bad, String.format(Locale.ROOT, "[%d,%d,%d]…[%d,%d,%d]",
                        p.x1(), p.y1(), p.z1(), p.x2(), p.y2(), p.z2())));
            }
        }
        return missing;
    }

    /** Whether the collector works right now and, if not, why. */
    public Optional<String> problem(Collector col) {
        HeartholdConfig c = config.get();
        if (regionAt(col.world(), col.x(), col.z()) == null) {
            return Optional.of(Lang.tr("collector.problem.no-region"));
        }
        Optional<Sphere> heart = territory.heartOf(col.clanId());
        if (heart.isEmpty() || !heart.get().isActive(System.currentTimeMillis())) {
            return Optional.of(Lang.tr("collector.problem.heart-inactive"));
        }
        if (!economy.isProtected(col.clanId())) {
            return Optional.of(Lang.tr("collector.problem.unpaid"));
        }
        if (!col.structureOk()) {
            return Optional.of(Lang.tr("collector.problem.structure"));
        }
        return Optional.empty();
    }

    /** Called once every interval-minutes. */
    public void tick() {
        long now = System.currentTimeMillis();
        double hours = (now - lastTickAt) / 3_600_000.0;
        lastTickAt = now;

        Map<UUID, Integer> perClan = new HashMap<>();
        collectors.values().forEach(col -> perClan.merge(col.clanId(), 1, Integer::sum));

        for (Collector col : collectors.values()) {
            produce(col, hours, perClan.getOrDefault(col.clanId(), 1));
        }
        saveBuffers();
    }

    /** Production of one collector over {@code hours} hours; true if the collector worked. */
    private boolean produce(Collector col, double hours, int clanCount) {
        MapConfig.CollectorSettings s = config.get().map().collector();
        boolean loaded = col.isLoaded();
        if (loaded) {
            col.structureOk = missingParts(col.block()).isEmpty();
        }
        boolean worked = false;
        if (problem(col).isEmpty()) {
            worked = true;
            MapConfig.Region region = regionAt(col.world(), col.x(), col.z());
            double eff = s.efficiencyFor(clanCount);
            double room = s.maxItems() - col.buffered() - (loaded ? stored(col) : 0);
            for (var e : region.perHour().entrySet()) {
                if (room <= 0) {
                    break;
                }
                double add = Math.min(room, e.getValue() * eff * hours);
                double toTribute = tribute.divert(col.clanId(), e.getKey(), add);
                col.buffer.merge(e.getKey(), add - toTribute, Double::sum);
                room -= add;
            }
        }
        if (loaded) {
            flush(col);
        }
        return worked;
    }

    /** For testing: immediately grant the clan's collector output for {@code hours} hours. Returns (total, worked). */
    public int[] fastForward(UUID clanId, double hours) {
        int count = (int) countOf(clanId);
        int total = 0;
        int worked = 0;
        for (Collector col : collectors.values()) {
            if (col.clanId().equals(clanId)) {
                total++;
                if (produce(col, hours, count)) {
                    worked++;
                }
            }
        }
        saveBuffers();
        return new int[]{total, worked};
    }

    /** How many items are already in the barrel. */
    private int stored(Collector col) {
        if (!(col.block().getState(false) instanceof Barrel barrel)) {
            return 0;
        }
        int n = 0;
        for (ItemStack item : barrel.getInventory().getContents()) {
            if (item != null) {
                n += item.getAmount();
            }
        }
        return n;
    }

    /** Moves whole units from the buffer into the barrel, as many as fit. */
    public void flush(Collector col) {
        if (!(col.block().getState(false) instanceof Barrel barrel)) {
            return;
        }
        Inventory inv = barrel.getInventory();
        for (var e : col.buffer.entrySet()) {
            int whole = (int) Math.floor(e.getValue() + 1e-9);
            if (whole <= 0) {
                continue;
            }
            int leftover = inv.addItem(new ItemStack(e.getKey(), whole)).values().stream()
                    .mapToInt(ItemStack::getAmount).sum();
            e.setValue(e.getValue() - (whole - leftover));
        }
        col.buffer.values().removeIf(v -> v <= 1e-9);
    }

    public void saveBuffers() {
        Connection conn = db.connection();
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement del = conn.prepareStatement("DELETE FROM collector_buffer");
                 PreparedStatement ins = conn.prepareStatement(
                         "INSERT INTO collector_buffer (collector_id, material, amount) VALUES (?, ?, ?)")) {
                del.executeUpdate();
                for (Collector col : collectors.values()) {
                    for (var e : col.buffer.entrySet()) {
                        ins.setString(1, col.id().toString());
                        ins.setString(2, e.getKey().name());
                        ins.setDouble(3, e.getValue());
                        ins.addBatch();
                    }
                }
                ins.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "Could not save collector buffers", e);
        }
    }

    /** Hourly output of one clan collector, accounting for diminishing returns. */
    public Map<Material, Double> ratePerHour(Collector col) {
        HeartholdConfig c = config.get();
        MapConfig.Region region = regionAt(col.world(), col.x(), col.z());
        if (region == null) {
            return Map.of();
        }
        double eff = c.map().collector().efficiencyFor((int) countOf(col.clanId()));
        Map<Material, Double> result = new HashMap<>();
        region.perHour().forEach((m, v) -> result.put(m, v * eff));
        return result;
    }
}
