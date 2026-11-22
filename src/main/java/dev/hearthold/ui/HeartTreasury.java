package dev.hearthold.ui;

import dev.hearthold.clan.Clan;
import dev.hearthold.economy.EconomyService;
import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Base heart treasury: a chest window holding upkeep emeralds. Emeralds (and emerald blocks) put in are credited
 * to the balance, ones taken out are debited; upkeep gradually eats into the balance. One person at a time.
 */
public final class HeartTreasury implements Listener {

    private static final int SIZE = 54;

    private final EconomyService economy;
    /** Clan → who currently has the treasury open. */
    private final Map<UUID, UUID> openBy = new HashMap<>();

    public HeartTreasury(EconomyService economy) {
        this.economy = economy;
    }

    private static final class Holder implements InventoryHolder {
        final Clan clan;
        final int shown;
        Inventory inventory;

        Holder(Clan clan, int shown) {
            this.clan = clan;
            this.shown = shown;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public void open(Player player, Clan clan) {
        if (!clan.hasAccess(player.getUniqueId(), Clan.Tag.TREASURER)) {
            player.sendActionBar(Component.text(Lang.tr("heart.treasury.no-access"), NamedTextColor.RED));
            return;
        }
        UUID holder = openBy.get(clan.id());
        if (holder != null && !holder.equals(player.getUniqueId()) && Bukkit.getPlayer(holder) != null) {
            player.sendActionBar(Component.text(Lang.tr("heart.treasury.busy", Bukkit.getPlayer(holder).getName()),
                    NamedTextColor.RED));
            return;
        }
        double balance = economy.emeralds(clan.id());
        int whole = (int) Math.min(Math.floor(balance), SIZE * 64L * 9);
        double cost = economy.dailyCost(clan.id());
        String title = Lang.tr("heart.treasury.title", String.format(Locale.ROOT, "%.1f", balance),
                String.format(Locale.ROOT, "%.1f", cost));
        // Blocks first, the remainder as emeralds; whatever doesn't fit stays on the balance, unseen.
        ItemStack[] contents = new ItemStack[SIZE];
        int blocks = whole / 9;
        int singles = whole % 9;
        int shown = 0;
        int slot = 0;
        while (blocks > 0 && slot < SIZE) {
            int n = Math.min(64, blocks);
            contents[slot++] = new ItemStack(Material.EMERALD_BLOCK, n);
            blocks -= n;
            shown += n * 9;
        }
        if (singles > 0 && slot < SIZE) {
            contents[slot] = new ItemStack(Material.EMERALD, singles);
            shown += singles;
        }
        Holder h = new Holder(clan, shown);
        Inventory finalInv = Bukkit.createInventory(h, SIZE, Component.text(title));
        finalInv.setContents(contents);
        h.inventory = finalInv;
        openBy.put(clan.id(), player.getUniqueId());
        player.openInventory(finalInv);
    }

    private static boolean isEmerald(ItemStack item) {
        return item != null && (item.getType() == Material.EMERALD || item.getType() == Material.EMERALD_BLOCK)
                && !item.hasItemMeta();
    }

    private static int value(ItemStack item) {
        return isEmerald(item) ? item.getAmount() * (item.getType() == Material.EMERALD_BLOCK ? 9 : 1) : 0;
    }

    /** Only emeralds and emerald blocks may be put into the treasury. */
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder)) {
            return;
        }
        boolean intoTop = event.getClickedInventory() == event.getView().getTopInventory();
        ItemStack moving = null;
        if (intoTop && (event.getAction().name().startsWith("PLACE") || event.getAction() == InventoryAction.SWAP_WITH_CURSOR)) {
            moving = event.getCursor();
        } else if (!intoTop && event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            moving = event.getCurrentItem();
        } else if (intoTop && event.getAction() == InventoryAction.HOTBAR_SWAP) {
            moving = event.getWhoClicked().getInventory().getItem(event.getHotbarButton());
        }
        if (moving != null && !moving.isEmpty() && !isEmerald(moving)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder && !isEmerald(event.getOldCursor())) {
            int top = event.getView().getTopInventory().getSize();
            if (event.getRawSlots().stream().anyMatch(s -> s < top)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder h)) {
            return;
        }
        openBy.remove(h.clan.id());
        int now = 0;
        Player player = (Player) event.getPlayer();
        for (ItemStack item : event.getInventory().getContents()) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            if (isEmerald(item)) {
                now += value(item);
            } else {
                player.getInventory().addItem(item).values()
                        .forEach(rest -> player.getWorld().dropItem(player.getLocation(), rest));
            }
        }
        int delta = now - h.shown;
        if (delta > 0) {
            economy.deposit(h.clan, delta);
            player.sendActionBar(Component.text(Lang.tr("heart.treasury.deposited", delta), NamedTextColor.GREEN));
        } else if (delta < 0) {
            economy.adjust(h.clan, delta);
            player.sendActionBar(Component.text(Lang.tr("heart.treasury.withdrawn", -delta), NamedTextColor.GOLD));
        }
    }
}
