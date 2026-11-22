package dev.hearthold.build;

import dev.hearthold.base.BaseItems;
import dev.hearthold.base.TerritoryService;
import dev.hearthold.clan.Clan;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.health.BlockKey;
import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Slow building: to place a block you must hold right-click on one spot for a few seconds.
 * <p>
 * While right-click is held, the client retries placement every 4 ticks. Each attempt is cancelled
 * and counted towards progress; once the required time is reached, the next attempt goes through as a normal
 * placement. If the player releases the button or changes the spot or block, progress starts over.
 */
public final class BuildListener implements Listener {

    /** Pause between attempts after which right-click counts as released (the client repeats every 200 ms). */
    private static final long GAP_MS = 500;
    private static final int BAR_LENGTH = 10;

    private record Progress(BlockKey target, Material material, long startedAt, long lastAt) { }

    private final TerritoryService territory;
    private final BaseItems baseItems;
    private final Supplier<HeartholdConfig> config;
    private final Map<UUID, Progress> progress = new HashMap<>();
    /** Who builds fast besides the own clan (e.g. someone invited to a plot). */
    private java.util.function.BiPredicate<Player, Block> fastBuild = (p, b) -> false;

    public void setFastBuild(java.util.function.BiPredicate<Player, Block> predicate) {
        this.fastBuild = predicate;
    }

    /** Whether the player's clan is at war (active phase) with the base owner clan. */
    private java.util.function.BiPredicate<Player, Clan> atWarWith = (p, c) -> false;

    public void setAtWarWith(java.util.function.BiPredicate<Player, Clan> predicate) {
        this.atWarWith = predicate;
    }

    /**
     * Why the player cannot build here (null if they can): on a foreign protected base one may build only
     * during a war with its clan. The own base, a plot the player is invited to, and an unpaid base (treated as
     * wilderness) are not restricted.
     */
    public String foreignDenial(Player player, Block block) {
        if (player.getGameMode() == GameMode.CREATIVE || fastBuild.test(player, block)) {
            return null;
        }
        Optional<Clan> claim = territory.claimOf(block);
        if (claim.isPresent() && !claim.get().isMember(player.getUniqueId()) && isRedstone(block.getType())) {
            return Lang.tr("misc.build.no-redstone");
        }
        Optional<Clan> owner = territory.ownerOf(block);
        if (owner.isEmpty() || owner.get().isMember(player.getUniqueId()) || atWarWith.test(player, owner.get())) {
            return null;
        }
        return Lang.tr("misc.build.foreign-base", owner.get().name());
    }

    private static final java.util.Set<Material> REDSTONE = java.util.EnumSet.of(
            Material.REDSTONE_WIRE, Material.REDSTONE_BLOCK, Material.REDSTONE_TORCH, Material.REDSTONE_WALL_TORCH,
            Material.REPEATER, Material.COMPARATOR, Material.OBSERVER, Material.PISTON, Material.STICKY_PISTON,
            Material.PISTON_HEAD, Material.DISPENSER, Material.DROPPER, Material.CRAFTER, Material.LEVER,
            Material.TRIPWIRE_HOOK, Material.TRIPWIRE, Material.TARGET, Material.DAYLIGHT_DETECTOR,
            Material.REDSTONE_LAMP, Material.TNT, Material.SCULK_SENSOR, Material.CALIBRATED_SCULK_SENSOR,
            Material.LIGHTNING_ROD, Material.SLIME_BLOCK, Material.HONEY_BLOCK, Material.POWERED_RAIL,
            Material.DETECTOR_RAIL, Material.ACTIVATOR_RAIL, Material.HOPPER);

    /** Redstone and mechanisms: dust, signal sources, pistons, dispensers and everything they move. */
    static boolean isRedstone(Material m) {
        return REDSTONE.contains(m) || org.bukkit.Tag.BUTTONS.isTagged(m) || org.bukkit.Tag.PRESSURE_PLATES.isTagged(m);
    }

    private boolean denyForeign(Player player, Block block) {
        String denial = foreignDenial(player, block);
        if (denial != null) {
            player.sendActionBar(Component.text(denial, NamedTextColor.RED));
            return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(org.bukkit.event.player.PlayerBucketEmptyEvent event) {
        if (denyForeign(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    public BuildListener(TerritoryService territory, BaseItems baseItems, Supplier<HeartholdConfig> config) {
        this.territory = territory;
        this.baseItems = baseItems;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || baseItems.kindOf(event.getItemInHand()).isPresent()) {
            return;
        }
        Block block = event.getBlockPlaced();
        if (denyForeign(player, block)) {
            event.setCancelled(true);
            progress.remove(player.getUniqueId());
            return;
        }
        Material material = block.getType();
        HeartholdConfig.BuildSettings b = config.get().build();
        if ((b.onlySolid() && !material.isSolid()) || b.instantBlocks().contains(material)) {
            return;
        }

        double seconds = secondsFor(player, block, b);
        if (seconds <= 0) {
            return;
        }

        long now = System.currentTimeMillis();
        BlockKey key = BlockKey.of(block);
        Progress p = progress.get(player.getUniqueId());
        if (p == null || !p.target().equals(key) || p.material() != material || now - p.lastAt() > GAP_MS) {
            p = new Progress(key, material, now, now);
        } else {
            p = new Progress(key, material, p.startedAt(), now);
        }

        double elapsed = (now - p.startedAt()) / 1000.0;
        if (elapsed >= seconds) {
            progress.remove(player.getUniqueId());
            return; // time reached: the block is placed
        }
        progress.put(player.getUniqueId(), p);
        event.setCancelled(true);
        player.sendActionBar(render(elapsed, seconds));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        progress.remove(event.getPlayer().getUniqueId());
    }

    /** Own base: fast; wilderness: slow; enemy base during war: even slower. */
    private double secondsFor(Player player, Block block, HeartholdConfig.BuildSettings b) {
        // The own base builds fast even with unpaid upkeep; a foreign unpaid base counts as wilderness.
        if (fastBuild.test(player, block)
                || territory.claimOf(block).filter(c -> c.isMember(player.getUniqueId())).isPresent()) {
            return b.ownBaseSeconds();
        }
        Optional<Clan> owner = territory.ownerOf(block);
        return owner.isEmpty() ? b.wildSeconds() : b.warBaseSeconds();
    }

    private static Component render(double elapsed, double total) {
        int filled = (int) Math.round(BAR_LENGTH * Math.min(1.0, elapsed / total));
        return Component.text(Lang.tr("misc.build.progress") + " ", NamedTextColor.GRAY)
                .append(Component.text("▮".repeat(filled), NamedTextColor.GREEN))
                .append(Component.text("▮".repeat(BAR_LENGTH - filled), NamedTextColor.DARK_GRAY))
                .append(Component.text(Lang.tr("misc.build.seconds",
                        String.format(Locale.ROOT, "%.1f", elapsed), String.format(Locale.ROOT, "%.1f", total)),
                        NamedTextColor.GRAY));
    }
}
