package dev.hearthold.base;

import dev.hearthold.i18n.Lang;
import org.bukkit.block.Block;

import java.util.UUID;

/**
 * A clan territory sphere: base heart, node or proxy.
 * Before {@code activeAt} the sphere already occupies space (nothing may intersect it) but does not protect blocks yet.
 */
public record Sphere(UUID id, UUID clanId, Kind kind, String world, int x, int y, int z, int radius,
                     long placedAt, long activeAt) {

    public enum Kind {
        HEART,
        NODE,
        /** A separate extra base (outpost): expensive, vulnerable right away, breakable by siege. */
        PROXY;

        /** Translated display name. */
        public String title() {
            return Lang.tr("base.kind." + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    public boolean isActive(long now) {
        return now >= activeAt;
    }

    public boolean isAt(Block block) {
        return block.getWorld().getName().equals(world)
                && block.getX() == x && block.getY() == y && block.getZ() == z;
    }

    /** Whether the block centre lies inside the sphere. */
    public boolean covers(String worldName, int bx, int by, int bz) {
        return covers(worldName, bx, by, bz, radius);
    }

    /** Same, but with the radius capped at {@code maxRadius} (newbie protection shrinks the territory). */
    public boolean covers(String worldName, int bx, int by, int bz, int maxRadius) {
        if (!world.equals(worldName)) {
            return false;
        }
        long r = Math.min(radius, maxRadius);
        long dx = bx - x, dy = by - y, dz = bz - z;
        return dx * dx + dy * dy + dz * dz <= r * r;
    }

    /** Whether two spheres intersect. */
    public boolean intersects(String worldName, int ox, int oy, int oz, int otherRadius) {
        if (!world.equals(worldName)) {
            return false;
        }
        long dx = ox - x, dy = oy - y, dz = oz - z;
        long sum = (long) radius + otherRadius;
        return dx * dx + dy * dy + dz * dz < sum * sum;
    }

    /** Horizontal distance between centres (for neighbour alerts). */
    public double horizontalDistance(String worldName, int ox, int oz) {
        if (!world.equals(worldName)) {
            return Double.MAX_VALUE;
        }
        return Math.hypot(ox - x, oz - z);
    }

    public String describe() {
        return Lang.tr("base.sphere.describe", kind.title(), world, x, y, z, radius);
    }
}
