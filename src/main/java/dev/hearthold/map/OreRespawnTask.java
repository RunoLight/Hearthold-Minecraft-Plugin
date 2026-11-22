package dev.hearthold.map;

import dev.hearthold.config.Area;
import dev.hearthold.config.MapConfig;
import dev.hearthold.config.HeartholdConfig;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Ore respawn in canyons and shared mines: on a schedule the server turns random exposed stone blocks
 * in the walls of neutral zones into ore. Only loaded chunks are touched, so the world is not loaded.
 */
public final class OreRespawnTask implements Runnable {

    private static final BlockFace[] FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final Supplier<HeartholdConfig> config;
    private long lastRunAt = System.currentTimeMillis();

    public OreRespawnTask(Supplier<HeartholdConfig> config) {
        this.config = config;
    }

    /** Called once a minute; decides itself whether interval-minutes has passed. */
    @Override
    public void run() {
        HeartholdConfig c = config.get();
        MapConfig.OreRespawn o = c.map().oreRespawn();
        long now = System.currentTimeMillis();
        if (now - lastRunAt < o.intervalMinutes() * 60_000L) {
            return;
        }
        lastRunAt = now;
        runNow();
    }

    /** Respawns right now; returns how much ore appeared. */
    public int runNow() {
        HeartholdConfig c = config.get();
        MapConfig.OreRespawn o = c.map().oreRespawn();
        if (o.weights().isEmpty() || o.perRun() == 0) {
            return 0;
        }
        int total = 0;
        for (Area zone : c.neutralZones()) {
            if (o.zones().contains(zone.name())) {
                total += respawn(zone, o);
            }
        }
        return total;
    }

    private int respawn(Area zone, MapConfig.OreRespawn o) {
        World world = Bukkit.getWorld(zone.world());
        if (world == null) {
            return 0;
        }
        int minY = Math.max(zone.minY(), world.getMinHeight());
        int maxY = Math.min(zone.maxY(), world.getMaxHeight() - 1);
        if (minY > maxY) {
            return 0;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int placed = 0;
        for (int i = 0; i < o.attempts() && placed < o.perRun(); i++) {
            int x = r.nextInt(zone.minX(), zone.maxX() + 1);
            int y = r.nextInt(minY, maxY + 1);
            int z = r.nextInt(zone.minZ(), zone.maxZ() + 1);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            Block block = world.getBlockAt(x, y, z);
            if (!o.hosts().contains(block.getType()) || !exposed(block)) {
                continue;
            }
            Material ore = pick(o.weights(), r);
            if (block.getType().name().contains("DEEPSLATE")) {
                Material deep = Material.matchMaterial("DEEPSLATE_" + ore.name());
                if (deep != null) {
                    ore = deep;
                }
            }
            block.setType(ore, false);
            placed++;
        }
        return placed;
    }

    /** A wall block: at least one face touches air. */
    private static boolean exposed(Block block) {
        for (BlockFace f : FACES) {
            if (block.getRelative(f).getType().isAir()) {
                return true;
            }
        }
        return false;
    }

    private static Material pick(Map<Material, Double> weights, ThreadLocalRandom r) {
        double sum = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        double roll = r.nextDouble() * sum;
        List<Map.Entry<Material, Double>> entries = List.copyOf(weights.entrySet());
        for (var e : entries) {
            roll -= e.getValue();
            if (roll <= 0) {
                return e.getKey();
            }
        }
        return entries.getLast().getKey();
    }
}
