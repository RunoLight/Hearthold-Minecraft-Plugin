package dev.hearthold.collector;

import dev.hearthold.config.MapConfig;
import dev.hearthold.i18n.Lang;
import dev.hearthold.storage.Database;
import org.bukkit.Material;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resources assigned to a chunk by an admin. If a chunk has an assignment, a collector in it produces
 * exactly that instead of the region's resources from config.yml.
 */
public final class ChunkResourceService {

    public record Key(String world, int cx, int cz) {
        public static Key ofBlock(String world, int x, int z) {
            return new Key(world, x >> 4, z >> 4);
        }
    }

    private final Database db;
    private final Logger log;
    private final Map<Key, Map<Material, Double>> chunks = new HashMap<>();

    public ChunkResourceService(Database db, Logger log) {
        this.db = db;
        this.log = log;
    }

    public void loadAll() {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT world, cx, cz, material, per_hour FROM chunk_resources");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Material m = Material.matchMaterial(rs.getString(4));
                if (m != null) {
                    chunks.computeIfAbsent(new Key(rs.getString(1), rs.getInt(2), rs.getInt(3)),
                            k -> new EnumMap<>(Material.class)).put(m, rs.getDouble(5));
                }
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "Could not load chunk resources", e);
        }
    }

    public Map<Material, Double> of(Key key) {
        return Collections.unmodifiableMap(chunks.getOrDefault(key, Map.of()));
    }

    /** Sets a resource's output in a chunk; 0 or less removes the resource. */
    public void set(Key key, Material material, double perHour) {
        try (PreparedStatement ps = db.connection().prepareStatement(perHour > 0
                ? "INSERT OR REPLACE INTO chunk_resources (world, cx, cz, material, per_hour) VALUES (?, ?, ?, ?, ?)"
                : "DELETE FROM chunk_resources WHERE world = ? AND cx = ? AND cz = ? AND material = ?")) {
            ps.setString(1, key.world());
            ps.setInt(2, key.cx());
            ps.setInt(3, key.cz());
            ps.setString(4, material.name());
            if (perHour > 0) {
                ps.setDouble(5, perHour);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Could not save chunk resource", e);
            return;
        }
        if (perHour > 0) {
            chunks.computeIfAbsent(key, k -> new EnumMap<>(Material.class)).put(material, perHour);
        } else if (chunks.containsKey(key)) {
            chunks.get(key).remove(material);
            if (chunks.get(key).isEmpty()) {
                chunks.remove(key);
            }
        }
    }

    public void clear(Key key) {
        for (Material m : Map.copyOf(of(key)).keySet()) {
            set(key, m, 0);
        }
    }

    /** Collector region at a point: the chunk assignment, otherwise the config region (or null). */
    public MapConfig.Region regionAt(MapConfig map, String world, int x, int z) {
        Key key = Key.ofBlock(world, x, z);
        Map<Material, Double> own = chunks.get(key);
        if (own != null && !own.isEmpty()) {
            return new MapConfig.Region(Lang.tr("collector.chunk-region", key.cx(), key.cz()), null, Map.copyOf(own));
        }
        return map.regionAt(world, x, z);
    }
}
