package dev.hearthold.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Simple interactive menu in a chest window: icons go into slots, clicking one runs its action.
 * All clicks in the window are cancelled, so items cannot be taken out of a menu.
 */
public final class Menu implements InventoryHolder {

    private final Inventory inventory;
    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();

    public Menu(int rows, Component title) {
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    public Menu set(int slot, ItemStack icon, Consumer<Player> action) {
        inventory.setItem(slot, icon);
        if (action != null) {
            actions.put(slot, action);
        } else {
            actions.remove(slot);
        }
        return this;
    }

    public int size() {
        return inventory.getSize();
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Icon with a name and description lines (non-italic). */
    public static ItemStack icon(Material material, String name, String... lore) {
        return icon(material, 1, name, lore);
    }

    public static ItemStack icon(Material material, int amount, String name, String... lore) {
        ItemStack item = new ItemStack(material, Math.max(1, Math.min(amount, material.getMaxStackSize())));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        if (lore.length > 0) {
            meta.lore(Arrays.stream(lore).map(l -> (Component) Component.text(l, NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)).toList());
        }
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack icon(Material material, String name, List<String> lore) {
        return icon(material, name, lore.toArray(String[]::new));
    }

    /** Click handler for all menus. */
    public static final class Listen implements Listener {

        @EventHandler
        public void onClick(InventoryClickEvent event) {
            if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) {
                return;
            }
            event.setCancelled(true);
            if (event.getClickedInventory() != event.getView().getTopInventory()
                    || !(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            Consumer<Player> action = menu.actions.get(event.getSlot());
            if (action != null) {
                action.accept(player);
            }
        }

        @EventHandler
        public void onDrag(InventoryDragEvent event) {
            if (event.getView().getTopInventory().getHolder() instanceof Menu) {
                event.setCancelled(true);
            }
        }
    }
}
