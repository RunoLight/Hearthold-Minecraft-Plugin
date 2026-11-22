package dev.hearthold.craft;

import dev.hearthold.clan.ClanService;
import dev.hearthold.health.BlockKey;
import dev.hearthold.i18n.Lang;
import dev.hearthold.storage.Database;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Clan stations (enchanting table, anvil, brewing stand) and timed jobs.
 * <p>
 * A job is an item that "matures" in a station: it is already enchanted or anvil-crafted but is handed out
 * only after the time passes. The timer uses wall-clock time, so it runs while players are offline too.
 * Until the job is collected, the station is busy. If the station is broken, the item drops and can be stolen in a raid.
 */
public final class StationService {

    public enum Type {
        ENCHANTING("enchanting"),
        ANVIL("anvil"),
        BREWING("brewing");

        private final String key;

        Type(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public String title() {
            return Lang.tr("misc.station." + key);
        }

        public static Optional<Type> of(Material m) {
            return switch (m) {
                case ENCHANTING_TABLE -> Optional.of(ENCHANTING);
                case ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL -> Optional.of(ANVIL);
                case BREWING_STAND -> Optional.of(BREWING);
                default -> Optional.empty();
            };
        }
    }

    public record Station(BlockKey key, UUID clanId, Type type) { }

    public static final class Job {
        final UUID id;
        final BlockKey at;
        final Type station;
        final ItemStack item;
        final UUID clanId;
        final String player;
        final long startedAt;
        final long finishAt;
        TextDisplay display;

        Job(UUID id, BlockKey at, Type station, ItemStack item, UUID clanId, String player, long startedAt,
            long finishAt) {
            this.id = id;
            this.at = at;
            this.station = station;
            this.item = item;
            this.clanId = clanId;
            this.player = player;
            this.startedAt = startedAt;
            this.finishAt = finishAt;
        }

        public boolean isDone() {
            return System.currentTimeMillis() >= finishAt;
        }

        public UUID clanId() {
            return clanId;
        }

        public ItemStack item() {
            return item.clone();
        }

        public long finishAt() {
            return finishAt;
        }
    }

    private final Database db;
    private final Logger log;
    private final Map<BlockKey, Station> stations = new HashMap<>();
    private final Map<BlockKey, Job> jobs = new HashMap<>();

    public StationService(Database db, ClanService clans, Logger log) {
        this.db = db;
        this.log = log;
        clans.onDisband(clan -> stations.values().removeIf(s -> s.clanId().equals(clan.id())));
    }

    public void loadAll() {
        try (PreparedStatement ps = db.connection().prepareStatement("SELECT world, x, y, z, clan_id, type FROM stations");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                BlockKey k = new BlockKey(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4));
                stations.put(k, new Station(k, UUID.fromString(rs.getString(5)), Type.valueOf(rs.getString(6))));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load stations", e);
        }
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT id, world, x, y, z, station, item, clan_id, player, started_at, finish_at FROM craft_jobs");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                BlockKey k = new BlockKey(rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5));
                String clan = rs.getString(8);
                jobs.put(k, new Job(UUID.fromString(rs.getString(1)), k, Type.valueOf(rs.getString(6)),
                        ItemStack.deserializeBytes(rs.getBytes(7)), clan == null ? null : UUID.fromString(clan),
                        rs.getString(9), rs.getLong(10), rs.getLong(11)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load craft jobs", e);
        }
    }

    // --- Stations ---

    public Optional<Station> station(Block block) {
        return Optional.ofNullable(stations.get(BlockKey.of(block)));
    }

    public long count(UUID clanId, Type type) {
        return stations.values().stream().filter(s -> s.clanId().equals(clanId) && s.type() == type).count();
    }

    public void register(Block block, UUID clanId, Type type) {
        BlockKey k = BlockKey.of(block);
        stations.put(k, new Station(k, clanId, type));
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT OR REPLACE INTO stations (world, x, y, z, clan_id, type) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, k.world());
            ps.setInt(2, k.x());
            ps.setInt(3, k.y());
            ps.setInt(4, k.z());
            ps.setString(5, clanId.toString());
            ps.setString(6, type.name());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to save station", e);
        }
    }

    public void unregister(Block block) {
        BlockKey k = BlockKey.of(block);
        if (stations.remove(k) == null) {
            return;
        }
        try (PreparedStatement ps = db.connection().prepareStatement(
                "DELETE FROM stations WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
            ps.setString(1, k.world());
            ps.setInt(2, k.x());
            ps.setInt(3, k.y());
            ps.setInt(4, k.z());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to delete station", e);
        }
    }

    // --- Jobs ---

    public Optional<Job> job(Block block) {
        return Optional.ofNullable(jobs.get(BlockKey.of(block)));
    }

    public Job start(Block block, Type type, ItemStack item, UUID clanId, String player, double minutes) {
        long now = System.currentTimeMillis();
        BlockKey k = BlockKey.of(block);
        Job job = new Job(UUID.randomUUID(), k, type, item.clone(), clanId, player, now, now + (long) (minutes * 60_000));
        jobs.put(k, job);
        try (PreparedStatement ps = db.connection().prepareStatement("""
                INSERT INTO craft_jobs (id, world, x, y, z, station, item, clan_id, player, started_at, finish_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, job.id.toString());
            ps.setString(2, k.world());
            ps.setInt(3, k.x());
            ps.setInt(4, k.y());
            ps.setInt(5, k.z());
            ps.setString(6, type.name());
            ps.setBytes(7, item.serializeAsBytes());
            ps.setString(8, clanId == null ? null : clanId.toString());
            ps.setString(9, player);
            ps.setLong(10, job.startedAt);
            ps.setLong(11, job.finishAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to save craft job", e);
        }
        return job;
    }

    /** For tests: the clan's jobs (or all, if clanId is null) finish immediately. Returns their count. */
    public int completeNow(UUID clanId) {
        long now = System.currentTimeMillis();
        int count = 0;
        for (var e : jobs.entrySet()) {
            Job j = e.getValue();
            if (j.isDone() || (clanId != null && !clanId.equals(j.clanId))) {
                continue;
            }
            if (j.display != null) {
                j.display.remove();
            }
            e.setValue(new Job(j.id, j.at, j.station, j.item, j.clanId, j.player, j.startedAt, now));
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "UPDATE craft_jobs SET finish_at = ? WHERE id = ?")) {
                ps.setLong(1, now);
                ps.setString(2, j.id.toString());
                ps.executeUpdate();
            } catch (SQLException ex) {
                log.log(Level.WARNING, "Failed to save craft job", ex);
            }
            count++;
        }
        return count;
    }

    /** Removes a job (item handed out or dropped). */
    public void finish(Job job) {
        jobs.remove(job.at);
        if (job.display != null) {
            job.display.remove();
        }
        try (PreparedStatement ps = db.connection().prepareStatement("DELETE FROM craft_jobs WHERE id = ?")) {
            ps.setString(1, job.id.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to delete craft job", e);
        }
    }

    /** Once per second: timers above stations in loaded chunks. */
    public void tickDisplays() {
        for (Job job : jobs.values()) {
            World w = Bukkit.getWorld(job.at.world());
            if (w == null || !w.isChunkLoaded(job.at.x() >> 4, job.at.z() >> 4)) {
                if (job.display != null) {
                    job.display.remove();
                    job.display = null;
                }
                continue;
            }
            Component text = Component.text(name(job.item), NamedTextColor.AQUA).appendNewline()
                    .append(job.isDone()
                            ? Component.text(Lang.tr("misc.station.ready-collect"), NamedTextColor.GREEN)
                            : Component.text(remaining(job), NamedTextColor.YELLOW));
            if (job.display == null || !job.display.isValid()) {
                Location at = new Location(w, job.at.x() + 0.5, job.at.y() + 1.4, job.at.z() + 0.5);
                job.display = w.spawn(at, TextDisplay.class, td -> {
                    td.setPersistent(false);
                    td.setBillboard(Display.Billboard.CENTER);
                    td.text(text);
                });
            } else {
                job.display.text(text);
            }
        }
    }

    public void clearDisplays() {
        jobs.values().forEach(j -> {
            if (j.display != null) {
                j.display.remove();
                j.display = null;
            }
        });
    }

    public static String remaining(Job job) {
        long s = Math.max(0, (job.finishAt - System.currentTimeMillis()) / 1000);
        return s >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    public static String name(ItemStack item) {
        return item.getType().name().toLowerCase(Locale.ROOT);
    }
}
