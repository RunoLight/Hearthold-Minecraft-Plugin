package dev.hearthold.economy;

import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.storage.Database;
import org.bukkit.Chunk;
import org.bukkit.block.Block;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Chunk energy. Plant growth and animal breeding spend the chunk's energy.
 * <p>
 * Each chunk slowly regenerates energy up to its natural maximum (higher in fertile zones).
 * Above that, energy can only be poured in from the base heart with energy cells, up to the overall cap.
 * Only chunks that differ from their natural maximum are kept in memory.
 */
public final class ChunkEnergyService {

    public record ChunkKey(String world, int cx, int cz) {
        public static ChunkKey of(Block block) {
            return new ChunkKey(block.getWorld().getName(), block.getX() >> 4, block.getZ() >> 4);
        }

        public static ChunkKey of(Chunk chunk) {
            return new ChunkKey(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        }
    }

    private static final class State {
        double energy;
        long updatedAt;
    }

    private final Supplier<HeartholdConfig> config;
    private final Map<ChunkKey, State> chunks = new HashMap<>();

    public ChunkEnergyService(Supplier<HeartholdConfig> config) {
        this.config = config;
    }

    public double naturalMax(ChunkKey key) {
        HeartholdConfig.EnergySettings e = config.get().energy();
        return e.chunkNaturalMax() * e.fertility(key.world(), key.cx() * 16 + 8, key.cz() * 16 + 8);
    }

    public double regenPerHour(ChunkKey key) {
        HeartholdConfig.EnergySettings e = config.get().energy();
        return e.chunkRegenPerHour() * e.fertility(key.world(), key.cx() * 16 + 8, key.cz() * 16 + 8);
    }

    /** Current chunk energy, including regeneration since the last change. */
    public double energy(ChunkKey key) {
        State s = chunks.get(key);
        if (s == null) {
            return naturalMax(key);
        }
        settle(key, s, System.currentTimeMillis());
        return s.energy;
    }

    /** Spends energy if there is enough. */
    public boolean consume(ChunkKey key, double amount) {
        if (amount <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        State s = state(key, now);
        if (s.energy < amount) {
            return false;
        }
        s.energy -= amount;
        return true;
    }

    /** Sets chunk energy (admin), clamped between 0 and the cap. */
    public void set(ChunkKey key, double value) {
        State s = state(key, System.currentTimeMillis());
        s.energy = Math.max(0, Math.min(cap(key), value));
    }

    /** Chunk energy cap (including energy poured in from the heart). */
    public double cap(ChunkKey key) {
        return Math.max(config.get().energy().chunkMax(), naturalMax(key));
    }

    /** Chunk fertility multiplier. */
    public double fertility(ChunkKey key) {
        return config.get().energy().fertility(key.world(), key.cx() * 16 + 8, key.cz() * 16 + 8);
    }

    /** How much more can be poured into the chunk before the cap. */
    public double freeCapacity(ChunkKey key) {
        double cap = Math.max(config.get().energy().chunkMax(), naturalMax(key));
        return Math.max(0, cap - energy(key));
    }

    /** Pours energy in up to the cap; returns how much was actually added. */
    public double infuse(ChunkKey key, double amount) {
        long now = System.currentTimeMillis();
        State s = state(key, now);
        double cap = Math.max(config.get().energy().chunkMax(), naturalMax(key));
        double added = Math.max(0, Math.min(amount, cap - s.energy));
        s.energy += added;
        return added;
    }

    private State state(ChunkKey key, long now) {
        State s = chunks.get(key);
        if (s == null) {
            s = new State();
            s.energy = naturalMax(key);
            s.updatedAt = now;
            chunks.put(key, s);
        } else {
            settle(key, s, now);
        }
        return s;
    }

    /** Regenerates only up to the natural maximum; energy poured in above it neither decays nor grows. */
    private void settle(ChunkKey key, State s, long now) {
        double max = naturalMax(key);
        if (s.energy < max) {
            s.energy = Math.min(max, s.energy + regenPerHour(key) * (now - s.updatedAt) / 3_600_000.0);
        }
        s.updatedAt = now;
    }

    public int trackedCount() {
        return chunks.size();
    }

    public void saveAll(Database db) throws SQLException {
        long now = System.currentTimeMillis();
        chunks.entrySet().removeIf(e -> {
            settle(e.getKey(), e.getValue(), now);
            return Math.abs(e.getValue().energy - naturalMax(e.getKey())) < 1e-6;
        });
        Connection conn = db.connection();
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO chunk_energy (world, cx, cz, energy, updated_at) VALUES (?, ?, ?, ?, ?)")) {
            st.execute("DELETE FROM chunk_energy");
            for (var e : chunks.entrySet()) {
                ps.setString(1, e.getKey().world());
                ps.setInt(2, e.getKey().cx());
                ps.setInt(3, e.getKey().cz());
                ps.setDouble(4, e.getValue().energy);
                ps.setLong(5, e.getValue().updatedAt);
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

    /** Loads energy; regeneration during downtime is applied on first access. */
    public void loadAll(Database db) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT world, cx, cz, energy, updated_at FROM chunk_energy");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                State s = new State();
                s.energy = rs.getDouble(4);
                s.updatedAt = rs.getLong(5);
                chunks.put(new ChunkKey(rs.getString(1), rs.getInt(2), rs.getInt(3)), s);
            }
        }
    }
}
