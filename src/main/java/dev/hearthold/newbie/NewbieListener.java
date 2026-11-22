package dev.hearthold.newbie;

import dev.hearthold.base.Sphere;
import dev.hearthold.base.TerritoryService;
import dev.hearthold.clan.Clan;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Container limit on a base under newbie protection, so an invulnerable base does not become a warehouse. */
public final class NewbieListener implements Listener {

    private final NewbieService newbie;
    private final TerritoryService territory;
    private final Supplier<HeartholdConfig> config;

    public NewbieListener(NewbieService newbie, TerritoryService territory, Supplier<HeartholdConfig> config) {
        this.newbie = newbie;
        this.territory = territory;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!(event.getBlockPlaced().getState(false) instanceof Container)) {
            return;
        }
        Optional<Clan> owner = territory.claimOf(event.getBlockPlaced());
        if (owner.isEmpty() || !newbie.isProtected(owner.get().id())) {
            return;
        }
        int max = config.get().newbie().maxContainers();
        // Existing containers (the new block itself is counted too: it is already in the world).
        if (count(owner.get().id(), event.getBlockPlaced().getWorld()) > max) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text(Lang.tr("misc.newbie.container-limit", max), NamedTextColor.RED));
        }
    }

    /** Containers on clan territory: iterate block entities of chunks around the heart. */
    private int count(UUID clanId, World world) {
        Optional<Sphere> heart = territory.heartOf(clanId);
        if (heart.isEmpty() || !heart.get().world().equals(world.getName())) {
            return 0;
        }
        int r = Math.min(heart.get().radius(), config.get().newbie().radius());
        int n = 0;
        for (int cx = (heart.get().x() - r) >> 4; cx <= (heart.get().x() + r) >> 4; cx++) {
            for (int cz = (heart.get().z() - r) >> 4; cz <= (heart.get().z() + r) >> 4; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    continue;
                }
                Chunk chunk = world.getChunkAt(cx, cz);
                for (BlockState state : chunk.getTileEntities()) {
                    if (state instanceof Container && clanId.equals(territory.claimAt(world.getName(),
                            state.getX(), state.getY(), state.getZ()))) {
                        n++;
                    }
                }
            }
        }
        return n;
    }
}
