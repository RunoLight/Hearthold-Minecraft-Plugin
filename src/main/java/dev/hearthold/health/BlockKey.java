package dev.hearthold.health;

import org.bukkit.block.Block;

/** Block coordinates as a map key (no world reference, to avoid keeping it in memory). */
public record BlockKey(String world, int x, int y, int z) {

    public static BlockKey of(Block block) {
        return new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }
}
