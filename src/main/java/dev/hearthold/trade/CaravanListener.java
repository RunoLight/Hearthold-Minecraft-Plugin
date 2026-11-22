package dev.hearthold.trade;

import dev.hearthold.base.TerritoryService;
import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanService;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Caravans in the world.
 * <ul>
 *   <li>A suitcase cannot be dropped, put into a container (except a pack animal's chest) or used in crafting.</li>
 *   <li>Once a second: a courier standing 10 s in the recipient's base delivers the cargo; one back in their own
 *       base cancels the shipment; a rider on a treasury animal in their own base returns it.</li>
 *   <li>Right-click with a suitcase: the recipient accepts the cargo, an outsider opens and loots it, the sender cannot.</li>
 * </ul>
 */
public final class CaravanListener implements Listener {

    private record Stand(Location anchor, UUID zone, long since) { }

    /** A suitcase and the inventory holding it. */
    private record Held(Inventory inventory, int slot, ItemStack item, Suitcase.Data data) { }

    private final Suitcase suitcase;
    private final CaravanService caravans;
    private final Stable stable;
    private final CaravanLog log;
    private final TerritoryService territory;
    private final ClanService clans;
    private final Supplier<HeartholdConfig> config;
    private final Map<UUID, Stand> stands = new HashMap<>();

    public CaravanListener(Suitcase suitcase, CaravanService caravans, Stable stable, CaravanLog log,
                           TerritoryService territory, ClanService clans, Supplier<HeartholdConfig> config) {
        this.suitcase = suitcase;
        this.caravans = caravans;
        this.stable = stable;
        this.log = log;
        this.territory = territory;
        this.clans = clans;
        this.config = config;
    }

    // --- Standing still: delivery, cancellation, returning the animal ---

    /** Called once a second. */
    public void tick() {
        int need = config.get().trade().standSeconds();
        for (Player player : Bukkit.getOnlinePlayers()) {
            List<Held> held = suitcases(player);
            Entity vehicle = player.getVehicle();
            Optional<Stable.Entry> mount = vehicle == null ? Optional.empty() : stable.entryOf(vehicle);
            if (held.isEmpty() && mount.isEmpty()) {
                stands.remove(player.getUniqueId());
                continue;
            }
            UUID zone = territory.claimOf(player.getLocation().getBlock()).map(Clan::id).orElse(null);

            // Outside the own base: mark that the cargo and animal have "left"; after that cancel and return are possible.
            for (Held h : held) {
                if (!h.data().leftBase() && !h.data().fromClan().equals(zone)) {
                    suitcase.markLeft(h.item());
                    h.inventory().setItem(h.slot(), h.item());
                }
            }
            if (mount.isPresent() && !stable.clanOf(mount.get()).equals(zone) && !stable.hasLeftBase(vehicle)) {
                stable.markLeft(vehicle);
            }
            if (zone == null) {
                stands.remove(player.getUniqueId());
                continue;
            }
            List<Held> deliver = held.stream().filter(h -> h.data().toClan().equals(zone)).toList();
            List<Held> cancel = held.stream()
                    .filter(h -> h.data().fromClan().equals(zone) && h.data().leftBase()).toList();
            boolean returnMount = mount.isPresent() && stable.clanOf(mount.get()).equals(zone)
                    && stable.hasLeftBase(vehicle) && vehicle instanceof AbstractHorse;
            if (deliver.isEmpty() && cancel.isEmpty() && !returnMount) {
                stands.remove(player.getUniqueId());
                continue;
            }

            Location here = player.getLocation();
            Stand s = stands.get(player.getUniqueId());
            if (s == null || !zone.equals(s.zone()) || !here.getWorld().equals(s.anchor().getWorld())
                    || here.distanceSquared(s.anchor()) > 1.0) {
                s = new Stand(here, zone, System.currentTimeMillis());
                stands.put(player.getUniqueId(), s);
            }
            long seconds = (System.currentTimeMillis() - s.since()) / 1000;
            if (seconds < need) {
                String what = Lang.tr(!deliver.isEmpty() ? "trade.stand.deliver"
                        : !cancel.isEmpty() ? "trade.stand.cancel" : "trade.stand.return");
                player.sendActionBar(Component.text(Lang.tr("trade.stand.progress", what, seconds, need),
                        NamedTextColor.YELLOW));
                continue;
            }
            stands.remove(player.getUniqueId());
            for (Held h : deliver) {
                h.inventory().setItem(h.slot(), null);
                caravans.deliver(h.data(), player.getName());
            }
            for (Held h : cancel) {
                h.inventory().setItem(h.slot(), null);
                caravans.cancel(h.data(), player.getName());
                player.sendMessage(Component.text(Lang.tr("trade.caravan.cancelled"), NamedTextColor.YELLOW));
            }
            if (returnMount) {
                AbstractHorse horse = (AbstractHorse) vehicle;
                stable.store(mount.get(), horse);
                log.add(stable.clanOf(mount.get()), player.getName(), Lang.tr("trade.log.mount-returned"),
                        mount.get().kind().displayName());
                player.sendMessage(Component.text(Lang.tr("trade.stable.returned"), NamedTextColor.GREEN));
            }
        }
    }

    /** Suitcases in the player's inventory and in the chest of the animal they ride. */
    private List<Held> suitcases(Player player) {
        List<Held> result = new ArrayList<>();
        collect(player.getInventory(), result);
        if (player.getVehicle() instanceof AbstractHorse horse) {
            collect(horse.getInventory(), result);
        }
        return result;
    }

    private void collect(Inventory inv, List<Held> out) {
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null) {
                int slot = i;
                suitcase.read(item).ifPresent(d -> out.add(new Held(inv, slot, item, d)));
            }
        }
    }

    // --- Opening ---

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        Optional<Suitcase.Data> data = suitcase.read(item);
        if (data.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        Suitcase.Data d = data.get();
        Optional<Clan> own = clans.ofPlayer(player.getUniqueId());
        if (own.isPresent() && own.get().id().equals(d.fromClan())) {
            player.sendActionBar(Component.text(Lang.tr("trade.suitcase.own"),
                    NamedTextColor.RED));
            return;
        }
        player.getInventory().setItemInMainHand(null);
        if (own.isPresent() && own.get().id().equals(d.toClan())) {
            caravans.deliver(d, player.getName());
        } else {
            caravans.loot(d, player);
        }
    }

    // --- Suitcase protection ---

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (suitcase.is(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text(Lang.tr("trade.suitcase.no-drop"), NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        boolean horse = top.getHolder() instanceof AbstractHorse;
        boolean clickedTop = top.equals(event.getClickedInventory());
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        // A suitcase cannot go into a bundle and a bundle cannot go into a suitcase.
        if ((suitcase.is(cursor) && isBundle(current)) || (isBundle(cursor) && suitcase.is(current))) {
            event.setCancelled(true);
            return;
        }
        if (horse) {
            return;
        }
        boolean intoTop = switch (event.getAction()) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> clickedTop && suitcase.is(cursor);
            case MOVE_TO_OTHER_INVENTORY -> !clickedTop && top.getType() != InventoryType.CRAFTING && suitcase.is(current);
            case HOTBAR_SWAP -> clickedTop && suitcase.is(event.getHotbarButton() >= 0
                    ? event.getWhoClicked().getInventory().getItem(event.getHotbarButton())
                    : event.getWhoClicked().getInventory().getItemInOffHand());
            default -> false;
        };
        if (intoTop) {
            event.setCancelled(true);
            event.getWhoClicked().sendActionBar(Component.text(Lang.tr("trade.suitcase.no-container"), NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof AbstractHorse || !suitcase.is(event.getOldCursor())) {
            return;
        }
        if (event.getRawSlots().stream().anyMatch(slot -> slot < top.getSize())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (suitcase.is(event.getPlayer().getInventory().getItem(event.getHand()))
                && !(event.getRightClicked() instanceof AbstractHorse)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopper(InventoryPickupItemEvent event) {
        if (suitcase.is(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMobPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player) && suitcase.is(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onCraft(PrepareItemCraftEvent event) {
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (suitcase.is(item)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    // --- Treasury animals ---

    @EventHandler
    public void onMountDeath(EntityDeathEvent event) {
        stable.entryOf(event.getEntity()).ifPresent(e -> {
            UUID clanId = stable.clanOf(e);
            stable.delete(e);
            log.add(clanId, "—", Lang.tr("trade.log.mount-died"), e.kind().displayName());
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stands.remove(event.getPlayer().getUniqueId());
    }

    private static boolean isBundle(ItemStack item) {
        return item != null && item.getType().name().endsWith("BUNDLE");
    }
}
