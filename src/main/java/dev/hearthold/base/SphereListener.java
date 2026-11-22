package dev.hearthold.base;

import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanException;
import dev.hearthold.clan.ClanService;
import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Placing hearts and nodes, alerting neighbours, indestructible spheres. */
public final class SphereListener implements Listener {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final BaseItems items;
    private final TerritoryService territory;
    private final ClanService clans;
    /** Called after a heart is placed: (clan, item), used for patronage. */
    private java.util.function.BiConsumer<Clan, org.bukkit.inventory.ItemStack> heartPlaced = (c, i) -> { };

    public void onHeartPlaced(java.util.function.BiConsumer<Clan, org.bukkit.inventory.ItemStack> callback) {
        this.heartPlaced = this.heartPlaced.andThen(callback);
    }

    /** Called after a proxy is placed (a second base ends newbie protection). */
    private java.util.function.Consumer<Clan> proxyPlaced = c -> { };

    public void onProxyPlaced(java.util.function.Consumer<Clan> callback) {
        this.proxyPlaced = this.proxyPlaced.andThen(callback);
    }

    public SphereListener(BaseItems items, TerritoryService territory, ClanService clans) {
        this.items = items;
        this.territory = territory;
        this.clans = clans;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Optional<Sphere.Kind> kind = items.kindOf(event.getItemInHand());
        if (kind.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        Optional<Clan> clan = clans.ofPlayer(player.getUniqueId());
        boolean mayPlace = clan.isPresent() && (kind.get() == Sphere.Kind.HEART
                ? clan.get().isLeader(player.getUniqueId())
                : clan.get().hasAccess(player.getUniqueId(), Clan.Tag.BUILDER));
        if (!mayPlace) {
            event.setCancelled(true);
            player.sendMessage(Component.text(Lang.tr(kind.get() == Sphere.Kind.HEART
                    ? "base.place.leader-only" : "base.place.builder-only", kind.get().title()), NamedTextColor.RED));
            return;
        }
        Sphere sphere;
        try {
            sphere = territory.place(clan.get(), kind.get(), event.getBlockPlaced());
        } catch (ClanException e) {
            event.setCancelled(true);
            player.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
            return;
        }
        if (sphere.kind() == Sphere.Kind.HEART) {
            heartPlaced.accept(clan.get(), event.getItemInHand());
        } else if (sphere.kind() == Sphere.Kind.PROXY) {
            proxyPlaced.accept(clan.get());
        }
        long minutes = Math.max(0, (sphere.activeAt() - sphere.placedAt()) / 60_000L);
        ClanService.broadcast(clan.get(), Component.text(minutes == 0
                ? Lang.tr("base.place.placed-now", sphere.describe())
                : Lang.tr("base.place.placed-later", sphere.describe(), minutes),
                NamedTextColor.GREEN));

        if (minutes > 0) {
            String at = TIME.format(Instant.ofEpochMilli(sphere.activeAt()).atZone(ZoneId.systemDefault()));
            String what = switch (sphere.kind()) {
                case HEART -> Lang.tr("base.place.alert.heart");
                case NODE -> Lang.tr("base.place.alert.node");
                case PROXY -> Lang.tr("base.place.alert.proxy");
            };
            Component alert = Component.text(Lang.tr("base.place.alert", clan.get().name(), what,
                    sphere.x(), sphere.y(), sphere.z(), at), NamedTextColor.GOLD);
            for (UUID neighbour : territory.neighbours(clan.get().id(), sphere.world(), sphere.x(), sphere.z())) {
                clans.byId(neighbour).ifPresent(c -> ClanService.broadcast(c, alert));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        // A proxy breaks under normal siege rules (HP); the heart and nodes never do.
        Optional<Sphere> sphere = territory.sphereAt(event.getBlock());
        if (sphere.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        if (sphere.get().kind() != Sphere.Kind.PROXY) {
            event.setCancelled(true);
            player.sendMessage(Component.text(Lang.tr("base.break.indestructible"), NamedTextColor.RED));
            return;
        }
        // Only the leader removes the clan's own proxy, and only if nodes stay connected without it.
        Optional<Clan> own = clans.ofPlayer(player.getUniqueId());
        if (own.isPresent() && own.get().id().equals(sphere.get().clanId())) {
            String denial = !own.get().isLeader(player.getUniqueId()) ? Lang.tr("base.break.proxy-leader-only")
                    : territory.removalDenial(sphere.get());
            if (denial != null) {
                event.setCancelled(true);
                player.sendMessage(Component.text(denial, NamedTextColor.RED));
            }
        }
    }

    /** The proxy really broke: remove the sphere. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProxyBroken(BlockBreakEvent event) {
        territory.sphereAt(event.getBlock()).filter(s -> s.kind() == Sphere.Kind.PROXY).ifPresent(s -> {
            try {
                territory.removeNode(s);
            } catch (ClanException ignored) {
                // a proxy can always be removed
            }
            clans.byId(s.clanId()).ifPresent(c -> ClanService.broadcast(c,
                    Component.text(Lang.tr("base.break.proxy-destroyed", s.x(), s.y(), s.z()), NamedTextColor.RED)));
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityExplode(EntityExplodeEvent event) {
        removeSpheres(event.blockList());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockExplode(BlockExplodeEvent event) {
        removeSpheres(event.blockList());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBurn(BlockBurnEvent event) {
        if (territory.sphereAt(event.getBlock()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityChange(EntityChangeBlockEvent event) {
        if (territory.sphereAt(event.getBlock()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(b -> territory.sphereAt(b).isPresent())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(b -> territory.sphereAt(b).isPresent())) {
            event.setCancelled(true);
        }
    }

    private void removeSpheres(List<Block> blocks) {
        blocks.removeIf(b -> territory.sphereAt(b).isPresent());
    }
}
