package dev.hearthold.economy;

import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Plant growth and animal breeding spend chunk energy. Without enough energy, growth does not happen.
 */
public final class GrowthListener implements Listener {

    private final ChunkEnergyService chunks;
    private final Supplier<HeartholdConfig> config;

    public GrowthListener(ChunkEnergyService chunks, Supplier<HeartholdConfig> config) {
        this.chunks = chunks;
        this.config = config;
    }

    /** Crop growth stage, cactus, sugar cane, pumpkin and melon from a stem. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        if (!chunks.consume(ChunkEnergyService.ChunkKey.of(event.getBlock()), config.get().energy().costCropStage())) {
            event.setCancelled(true);
        }
    }

    /** Sapling into a tree, mushroom into a huge mushroom. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTree(StructureGrowEvent event) {
        Block at = event.getLocation().getBlock();
        if (!chunks.consume(ChunkEnergyService.ChunkKey.of(at), config.get().energy().costTree())) {
            event.setCancelled(true);
            if (event.getPlayer() != null) {
                notEnough(event.getPlayer(), at);
            }
        }
    }

    /** Bone meal costs energy on top of the growth it causes. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        if (!chunks.consume(ChunkEnergyService.ChunkKey.of(event.getBlock()), config.get().energy().costBonemeal())) {
            event.setCancelled(true);
            if (event.getPlayer() != null) {
                notEnough(event.getPlayer(), event.getBlock());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        Block at = event.getEntity().getLocation().getBlock();
        if (!chunks.consume(ChunkEnergyService.ChunkKey.of(at), config.get().energy().costBreed())) {
            event.setCancelled(true);
            if (event.getBreeder() instanceof Player p) {
                notEnough(p, at);
            }
        }
    }

    private void notEnough(Player player, Block at) {
        player.sendActionBar(Component.text(Lang.tr("economy.growth.no-energy", String.format(Locale.ROOT, "%.0f",
                chunks.energy(ChunkEnergyService.ChunkKey.of(at)))), NamedTextColor.RED));
    }
}
