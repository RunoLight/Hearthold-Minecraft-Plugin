package dev.hearthold.body;

import dev.hearthold.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Offline player bodies. A body stores an inventory snapshot taken at logout. If the body is killed, items drop
 * from the snapshot, and on login the player loses their inventory and appears at their respawn point.
 */
public final class BodyService {

    public static final class Body {
        final UUID player;
        final String playerName;
        UUID entityId;
        final String world;
        final double x, y, z;
        final ItemStack[] items;
        boolean killed;
        String killer;
        final long createdAt;

        Body(UUID player, String playerName, UUID entityId, String world, double x, double y, double z,
             ItemStack[] items, boolean killed, String killer, long createdAt) {
            this.player = player;
            this.playerName = playerName;
            this.entityId = entityId;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.items = items;
            this.killed = killed;
            this.killer = killer;
            this.createdAt = createdAt;
        }

        public UUID player() {
            return player;
        }

        public String playerName() {
            return playerName;
        }

        public boolean killed() {
            return killed;
        }

        public String killer() {
            return killer;
        }

        public ItemStack[] items() {
            return items;
        }

        public Optional<Location> location() {
            World w = Bukkit.getWorld(world);
            return w == null ? Optional.empty() : Optional.of(new Location(w, x, y, z));
        }

        public UUID entityId() {
            return entityId;
        }
    }

    private final Database db;
    private final Logger log;
    private final Map<UUID, Body> bodies = new HashMap<>();

    public BodyService(Database db, Logger log) {
        this.db = db;
        this.log = log;
    }

    public void loadAll() {
        try (PreparedStatement ps = db.connection().prepareStatement("""
                SELECT player_id, player_name, entity_id, world, x, y, z, items, killed, killer, created_at FROM bodies""");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Body b = new Body(UUID.fromString(rs.getString(1)), rs.getString(2), UUID.fromString(rs.getString(3)),
                        rs.getString(4), rs.getDouble(5), rs.getDouble(6), rs.getDouble(7),
                        ItemStack.deserializeItemsFromBytes(rs.getBytes(8)), rs.getInt(9) == 1, rs.getString(10),
                        rs.getLong(11));
                bodies.put(b.player, b);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load bodies", e);
        }
    }

    public Collection<Body> all() {
        return bodies.values();
    }

    public Optional<Body> of(UUID player) {
        return Optional.ofNullable(bodies.get(player));
    }

    public Optional<Body> byEntity(UUID entityId) {
        return bodies.values().stream().filter(b -> !b.killed && entityId.equals(b.entityId)).findFirst();
    }

    public Body create(UUID player, String name, Entity entity, ItemStack[] items) {
        Location l = entity.getLocation();
        Body b = new Body(player, name, entity.getUniqueId(), l.getWorld().getName(), l.getX(), l.getY(), l.getZ(),
                items, false, null, System.currentTimeMillis());
        bodies.put(player, b);
        try (PreparedStatement ps = db.connection().prepareStatement("""
                INSERT OR REPLACE INTO bodies (player_id, player_name, entity_id, world, x, y, z, items, killed, killer, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, NULL, ?)""")) {
            ps.setString(1, player.toString());
            ps.setString(2, name);
            ps.setString(3, b.entityId.toString());
            ps.setString(4, b.world);
            ps.setDouble(5, b.x);
            ps.setDouble(6, b.y);
            ps.setDouble(7, b.z);
            ps.setBytes(8, ItemStack.serializeItemsAsBytes(items));
            ps.setLong(9, b.createdAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to save body", e);
        }
        return b;
    }

    public void markKilled(Body b, String killer) {
        b.killed = true;
        b.killer = killer;
        try (PreparedStatement ps = db.connection().prepareStatement(
                "UPDATE bodies SET killed = 1, killer = ? WHERE player_id = ?")) {
            ps.setString(1, killer);
            ps.setString(2, b.player.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to mark body as killed", e);
        }
    }

    public void delete(Body b) {
        bodies.remove(b.player);
        try (PreparedStatement ps = db.connection().prepareStatement("DELETE FROM bodies WHERE player_id = ?")) {
            ps.setString(1, b.player.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to delete body", e);
        }
    }
}
