package dev.hearthold.plot;

import org.bukkit.block.Block;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A plot is a rectangular area inside a clan base with a plot heart at its center. Invited players
 * build, break and open containers inside it.
 */
public final class Plot {

    final UUID id;
    final UUID clanId;
    String name;
    final String world;
    final int x, y, z;
    int sx, sy, sz;
    double score;
    long ratedAt;
    final long createdAt;
    final Set<UUID> members = new HashSet<>();

    Plot(UUID id, UUID clanId, String name, String world, int x, int y, int z, int sx, int sy, int sz,
         double score, long ratedAt, long createdAt) {
        this.id = id;
        this.clanId = clanId;
        this.name = name;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.score = score;
        this.ratedAt = ratedAt;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public UUID clanId() {
        return clanId;
    }

    public String name() {
        return name;
    }

    public String world() {
        return world;
    }

    public double score() {
        return score;
    }

    public long ratedAt() {
        return ratedAt;
    }

    public Set<UUID> members() {
        return Set.copyOf(members);
    }

    public int minX() {
        return x - (sx - 1) / 2;
    }

    public int maxX() {
        return minX() + sx - 1;
    }

    public int minY() {
        return y - (sy - 1) / 2;
    }

    public int maxY() {
        return minY() + sy - 1;
    }

    public int minZ() {
        return z - (sz - 1) / 2;
    }

    public int maxZ() {
        return minZ() + sz - 1;
    }

    public boolean contains(String w, int bx, int by, int bz) {
        return world.equals(w) && bx >= minX() && bx <= maxX() && by >= minY() && by <= maxY()
                && bz >= minZ() && bz <= maxZ();
    }

    public boolean contains(Block b) {
        return contains(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    public boolean isHeart(Block b) {
        return b.getWorld().getName().equals(world) && b.getX() == x && b.getY() == y && b.getZ() == z;
    }

    public String size() {
        return sx + "×" + sy + "×" + sz;
    }
}
