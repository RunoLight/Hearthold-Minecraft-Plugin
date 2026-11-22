package dev.hearthold.weight;

import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.function.Supplier;

/**
 * Ender chests and shulker boxes accept only gear and consumables (list in weight.storage), not resources;
 * otherwise weight could be bypassed with pocket storage.
 */
public final class StorageListener implements Listener {

    private final Supplier<HeartholdConfig> config;

    public StorageListener(Supplier<HeartholdConfig> config) {
        this.config = config;
    }

    private static boolean restricted(Inventory inv) {
        return inv != null && (inv.getType() == InventoryType.ENDER_CHEST || inv.getType() == InventoryType.SHULKER_BOX);
    }

    private boolean allowed(ItemStack item) {
        return item == null || item.isEmpty() || config.get().weight().storageAllows(item.getType());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!restricted(top)) {
            return;
        }
        boolean clickedTop = top.equals(event.getClickedInventory());
        ItemStack incoming = switch (event.getAction()) {
            // Placing the cursor item into the top window.
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> clickedTop ? event.getCursor() : null;
            // Shift-click from below moves the item up.
            case MOVE_TO_OTHER_INVENTORY -> clickedTop ? null : event.getCurrentItem();
            // A number key or F in the top window puts the hotbar or off-hand item there.
            case HOTBAR_SWAP -> clickedTop ? hotbarItem(event) : null;
            default -> null;
        };
        if (!allowed(incoming)) {
            event.setCancelled(true);
            deny(event.getWhoClicked());
        }
    }

    private static ItemStack hotbarItem(InventoryClickEvent event) {
        int button = event.getHotbarButton();
        return button >= 0
                ? event.getWhoClicked().getInventory().getItem(button)
                : event.getWhoClicked().getInventory().getItemInOffHand();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!restricted(top) || allowed(event.getOldCursor())) {
            return;
        }
        int topSize = top.getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
            event.setCancelled(true);
            deny(event.getWhoClicked());
        }
    }

    /** Hoppers cannot load resources into a shulker box either. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent event) {
        if (restricted(event.getDestination()) && !allowed(event.getItem())) {
            event.setCancelled(true);
        }
    }

    private static void deny(HumanEntity who) {
        who.sendActionBar(Component.text(Lang.tr("weight.storage.denied"), NamedTextColor.RED));
    }
}
