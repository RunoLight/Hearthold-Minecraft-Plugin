package dev.hearthold.event;

import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.List;
import java.util.Optional;

/** Starts waves on right-click of the core, locks event chests until they open, event mobs drop no vanilla loot. */
public final class EventListener implements Listener {

    private final EventService events;

    public EventListener(EventService events) {
        this.events = events;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null) {
            return;
        }
        Block block = event.getClickedBlock();
        Optional<EventService.Site> core = events.coreAt(block);
        if (core.isPresent() && core.get().type() == EventService.Type.WAVE) {
            event.setCancelled(true);
            EventService.Site s = core.get();
            if (s.state() == EventService.State.READY) {
                String error = events.startWaves(s, event.getPlayer());
                if (error != null) {
                    event.getPlayer().sendActionBar(Component.text(error, NamedTextColor.RED));
                }
            } else {
                event.getPlayer().sendActionBar(Component.text(s.state() == EventService.State.COOLDOWN
                        ? Lang.tr("event.listener.state.cooldown", s.name(), s.state().title(), EventCommand.left(s.nextAt()))
                        : Lang.tr("event.listener.state", s.name(), s.state().title()),
                        NamedTextColor.YELLOW));
            }
            return;
        }
        if (block.getState(false) instanceof Container) {
            Optional<EventService.Site> site = events.siteAt(block.getLocation().add(0.5, 0, 0.5));
            if (site.isPresent() && site.get().type() == EventService.Type.CHEST
                    && site.get().state() != EventService.State.OPEN) {
                event.setCancelled(true);
                event.getPlayer().sendActionBar(Component.text(site.get().state() == EventService.State.ANNOUNCED
                        ? Lang.tr("event.listener.chests-locked.opens-in", site.get().name(), EventCommand.left(site.get().nextAt()))
                        : Lang.tr("event.listener.chests-locked", site.get().name()), NamedTextColor.YELLOW));
            }
        }
    }

    /** Event chests and the core cannot be broken, otherwise the lock could be bypassed. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text(Lang.tr("event.listener.protected"), NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        strip(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        strip(event.blockList());
    }

    /** Event mobs drop no vanilla loot: the reward is only at the core. */
    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (events.isEventMob(event.getEntity())) {
            event.getDrops().clear();
        }
    }

    private void strip(List<Block> blocks) {
        blocks.removeIf(this::isProtected);
    }

    private boolean isProtected(Block block) {
        if (events.coreAt(block).isPresent()) {
            return true;
        }
        if (!(block.getState(false) instanceof Container)) {
            return false;
        }
        return events.siteAt(block.getLocation().add(0.5, 0, 0.5))
                .filter(s -> s.type() == EventService.Type.CHEST).isPresent();
    }
}
