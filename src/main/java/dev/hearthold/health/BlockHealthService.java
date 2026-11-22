package dev.hearthold.health;

import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * HP of damaged and new blocks. Only blocks below maximum HP are stored:
 * an intact block takes no memory, and the entry is removed after full regeneration.
 */
public final class BlockHealthService {

    private static final class State {
        double hp;
        int max;
        long lastHitAt;
    }

    private final Supplier<HeartholdConfig> config;
    private final Map<BlockKey, State> damaged = new HashMap<>();
    /** Regen multiplier for a block (war fatigue of the base owner). */
    private java.util.function.ToDoubleFunction<BlockKey> regenMultiplier = k -> 1.0;

    public void setRegenMultiplier(java.util.function.ToDoubleFunction<BlockKey> f) {
        this.regenMultiplier = f;
    }

    public BlockHealthService(Supplier<HeartholdConfig> config) {
        this.config = config;
    }

    /** Current block HP (maximum if the block is not damaged). */
    public double hp(BlockKey key, int max) {
        State s = damaged.get(key);
        return s == null ? max : Math.min(s.hp, max);
    }

    public boolean isTracked(BlockKey key) {
        return damaged.containsKey(key);
    }

    /** Removes HP and returns the remainder (not below zero). */
    public double hit(BlockKey key, int max, int damage) {
        State s = damaged.computeIfAbsent(key, k -> {
            State fresh = new State();
            fresh.hp = max;
            return fresh;
        });
        s.max = max;
        s.hp = Math.max(0, Math.min(s.hp, max) - damage);
        s.lastHitAt = System.currentTimeMillis();
        return s.hp;
    }

    /** A damaged block: key, current and maximum HP. */
    public record Damaged(BlockKey key, double hp, int max) { }

    /**
     * Blocks in the radius that someone hit (excluding freshly placed ones, whose HP grows from 1 on its own).
     */
    public java.util.List<Damaged> damagedNear(String world, int x, int y, int z, int radius) {
        java.util.List<Damaged> result = new java.util.ArrayList<>();
        long r2 = (long) radius * radius;
        for (var e : damaged.entrySet()) {
            BlockKey k = e.getKey();
            State s = e.getValue();
            if (s.lastHitAt == 0 || !k.world().equals(world)) {
                continue;
            }
            long dx = k.x() - x, dy = k.y() - y, dz = k.z() - z;
            if (dx * dx + dy * dy + dz * dz <= r2) {
                result.add(new Damaged(k, Math.min(s.hp, s.max), s.max));
            }
        }
        return result;
    }

    /** A new block: starts at 1 HP and immediately begins regenerating. */
    public void placed(BlockKey key, int max) {
        State s = new State();
        s.hp = 1;
        s.max = max;
        s.lastHitAt = 0;
        damaged.put(key, s);
    }

    /** The block was broken or replaced: its HP is no longer tracked. */
    public void forget(BlockKey key) {
        damaged.remove(key);
    }

    /** For tests: fully repairs tracked blocks matching the condition. Returns their count. */
    public int healWhere(java.util.function.Predicate<BlockKey> filter) {
        int before = damaged.size();
        damaged.keySet().removeIf(filter);
        return before - damaged.size();
    }

    public int trackedCount() {
        return damaged.size();
    }

    /** Called once per second. */
    public void tickRegen() {
        HeartholdConfig c = config.get();
        long now = System.currentTimeMillis();
        long delayMs = c.regenDelaySeconds() * 1000L;
        var it = damaged.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            State s = e.getValue();
            if (now - s.lastHitAt < delayMs) {
                continue;
            }
            s.hp += c.regenPerSecond() * regenMultiplier.applyAsDouble(e.getKey());
            if (s.hp >= s.max) {
                it.remove();
            }
        }
    }

    /** Fully rewrites the table with the current state. */
    public void saveAll(Database db) throws SQLException {
        Connection conn = db.connection();
        long now = System.currentTimeMillis();
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO block_health (world, x, y, z, hp, max_hp, last_hit_at, saved_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
            st.execute("DELETE FROM block_health");
            for (var e : damaged.entrySet()) {
                BlockKey k = e.getKey();
                State s = e.getValue();
                ps.setString(1, k.world());
                ps.setInt(2, k.x());
                ps.setInt(3, k.y());
                ps.setInt(4, k.z());
                ps.setDouble(5, s.hp);
                ps.setInt(6, s.max);
                ps.setLong(7, s.lastHitAt);
                ps.setLong(8, now);
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    /** Loads HP and applies regeneration for the time the server was offline. */
    public void loadAll(Database db) throws SQLException {
        HeartholdConfig c = config.get();
        long now = System.currentTimeMillis();
        long delayMs = c.regenDelaySeconds() * 1000L;
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT world, x, y, z, hp, max_hp, last_hit_at, saved_at FROM block_health");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                State s = new State();
                s.hp = rs.getDouble(5);
                s.max = rs.getInt(6);
                s.lastHitAt = rs.getLong(7);
                long regenFrom = Math.max(rs.getLong(8), s.lastHitAt + delayMs);
                if (now > regenFrom) {
                    s.hp += c.regenPerSecond() * (now - regenFrom) / 1000.0;
                }
                if (s.hp < s.max) {
                    damaged.put(new BlockKey(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)), s);
                }
            }
        }
    }
}
