package dev.hearthold.health;

import dev.hearthold.base.TerritoryService;
import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanService;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * Once per second highlights damaged blocks of their base nearby for clan members, showing where someone
 * tried to break in. Colour by remaining HP: yellow, orange, red. Only the member sees the particles.
 * Freshly placed blocks (growing from 1 HP on their own) are not highlighted.
 */
public final class DamageHighlightTask implements Runnable {

    private static final int RADIUS = 32;
    private static final Particle.DustOptions YELLOW = new Particle.DustOptions(Color.fromRGB(0xFFD23F), 1.0f);
    private static final Particle.DustOptions ORANGE = new Particle.DustOptions(Color.fromRGB(0xFF8A1F), 1.2f);
    private static final Particle.DustOptions RED = new Particle.DustOptions(Color.fromRGB(0xFF2A2A), 1.4f);
    /** Block face centres: particles are visible from any side. */
    private static final double[][] FACES = {
            {0.5, 1.05, 0.5}, {0.5, -0.05, 0.5}, {-0.05, 0.5, 0.5}, {1.05, 0.5, 0.5}, {0.5, 0.5, -0.05}, {0.5, 0.5, 1.05}};

    private final BlockHealthService health;
    private final TerritoryService territory;
    private final ClanService clans;

    public DamageHighlightTask(BlockHealthService health, TerritoryService territory, ClanService clans) {
        this.health = health;
        this.territory = territory;
        this.clans = clans;
    }

    @Override
    public void run() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            Optional<Clan> clan = clans.ofPlayer(p.getUniqueId());
            if (clan.isEmpty()) {
                continue;
            }
            UUID own = clan.get().id();
            Location l = p.getLocation();
            String world = l.getWorld().getName();
            for (BlockHealthService.Damaged d : health.damagedNear(world, l.getBlockX(), l.getBlockY(), l.getBlockZ(), RADIUS)) {
                BlockKey k = d.key();
                if (!own.equals(territory.claimAt(world, k.x(), k.y(), k.z()))) {
                    continue;
                }
                double ratio = d.max() <= 0 ? 1 : d.hp() / d.max();
                Particle.DustOptions dust = ratio > 0.6 ? YELLOW : ratio > 0.3 ? ORANGE : RED;
                for (double[] f : FACES) {
                    p.spawnParticle(Particle.DUST, k.x() + f[0], k.y() + f[1], k.z() + f[2], 1, 0.15, 0.15, 0.15, 0,
                            dust, true);
                }
            }
        }
    }
}
