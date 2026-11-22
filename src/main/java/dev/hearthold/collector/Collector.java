package dev.hearthold.collector;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Resource collector: a core barrel on a clan base into which the server deposits the region's resources. */
public final class Collector {

    private final UUID id;
    private final UUID clanId;
    private final String world;
    private final int x, y, z;
    private final long placedAt;
    /** Accumulated output not yet in the barrel (fractional remainders and whatever did not fit). */
    final Map<org.bukkit.Material, Double> buffer = new HashMap<>();
    /** Whether the structure was intact at the last check (not checked in unloaded chunks). */
    boolean structureOk = true;

    public Collector(UUID id, UUID clanId, String world, int x, int y, int z, long placedAt) {
        this.id = id;
        this.clanId = clanId;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.placedAt = placedAt;
    }

    public UUID id() {
        return id;
    }

    public UUID clanId() {
        return clanId;
    }

    public String world() {
        return world;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public long placedAt() {
        return placedAt;
    }

    public boolean isAt(Block block) {
        return block.getWorld().getName().equals(world) && block.getX() == x && block.getY() == y && block.getZ() == z;
    }

    public boolean isLoaded() {
        World w = Bukkit.getWorld(world);
        return w != null && w.isChunkLoaded(x >> 4, z >> 4);
    }

    /** The core block; call only for a loaded chunk. */
    public Block block() {
        return Bukkit.getWorld(world).getBlockAt(x, y, z);
    }

    public boolean structureOk() {
        return structureOk;
    }

    public double buffered() {
        return buffer.values().stream().mapToDouble(Double::doubleValue).sum();
    }
}
