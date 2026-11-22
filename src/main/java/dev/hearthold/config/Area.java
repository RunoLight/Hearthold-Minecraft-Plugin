package dev.hearthold.config;

/** A box-shaped world volume: a neutral zone (canyon, mine) or a fertile zone. */
public record Area(String name, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public static Area of(String name, String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Area(name, world,
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    /** Whether a sphere touches the zone: the zone point closest to the centre lies inside the sphere. */
    public boolean touchesSphere(String worldName, int cx, int cy, int cz, int radius) {
        if (!world.equals(worldName)) {
            return false;
        }
        long dx = cx - clamp(cx, minX, maxX);
        long dy = cy - clamp(cy, minY, maxY);
        long dz = cz - clamp(cz, minZ, maxZ);
        return dx * dx + dy * dy + dz * dz <= (long) radius * radius;
    }

    public boolean contains(String worldName, int x, int y, int z) {
        return world.equals(worldName)
                && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean containsXZ(String worldName, int x, int z) {
        return world.equals(worldName) && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public String describe() {
        return name + " (" + world + " " + minX + ", " + minY + ", " + minZ
                + " — " + maxX + ", " + maxY + ", " + maxZ + ")";
    }
}
