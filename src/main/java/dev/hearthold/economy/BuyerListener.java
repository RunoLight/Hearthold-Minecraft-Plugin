package dev.hearthold.economy;

import dev.hearthold.base.BaseItems;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.i18n.Lang;
import dev.hearthold.storage.Database;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.block.ShulkerBox;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Universal buyer: a villager NPC that buys any item for emeralds at a low price.
 * The player puts items into the buyer window and closes it: sold items are paid in emeralds,
 * unsold ones are returned. The fractional remainder accumulates on the player's account until the next sale.
 */
public final class BuyerListener implements Listener {

    private static final int SIZE = 27;

    /** Sell window. */
    private static final class SellWindow implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final NamespacedKey key;
    private final Database db;
    private final Supplier<HeartholdConfig> config;
    private final EnergyCell cells;
    private final BaseItems baseItems;
    private final Logger log;
    private final Map<UUID, Double> credit = new HashMap<>();

    public BuyerListener(Plugin plugin, Database db, Supplier<HeartholdConfig> config, EnergyCell cells, BaseItems baseItems) {
        this.key = new NamespacedKey(plugin, "buyer");
        this.db = db;
        this.config = config;
        this.cells = cells;
        this.baseItems = baseItems;
        this.log = plugin.getLogger();
    }

    public void loadAll() {
        try (PreparedStatement ps = db.connection().prepareStatement("SELECT player_id, amount FROM buyer_credit");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                credit.put(UUID.fromString(rs.getString(1)), rs.getDouble(2));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load buyer credit", e);
        }
    }

    public Villager spawn(Location at) {
        return at.getWorld().spawn(at, Villager.class, v -> {
            v.customName(Component.text(Lang.tr("economy.buyer.name"), NamedTextColor.GOLD));
            v.setCustomNameVisible(true);
            v.setAI(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setRemoveWhenFarAway(false);
            v.setProfession(Villager.Profession.NITWIT);
            v.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        });
    }

    public boolean isBuyer(Entity entity) {
        return entity.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!isBuyer(event.getRightClicked())) {
            return;
        }
        event.setCancelled(true);
        SellWindow holder = new SellWindow();
        holder.inventory = Bukkit.createInventory(holder, SIZE,
                Component.text(Lang.tr("economy.buyer.title")));
        event.getPlayer().openInventory(holder.inventory);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (isBuyer(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof SellWindow) || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        HeartholdConfig c = config.get();
        double total = 0;
        int sold = 0;
        List<ItemStack> returned = new ArrayList<>();
        for (ItemStack item : event.getInventory().getContents()) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            double price = unitPrice(item, c);
            if (price <= 0) {
                returned.add(item);
                continue;
            }
            total += price * item.getAmount();
            sold += item.getAmount();
        }
        event.getInventory().clear();
        returned.forEach(item -> give(player, item));

        if (sold == 0) {
            if (!returned.isEmpty()) {
                player.sendMessage(Component.text(Lang.tr("economy.buyer.not-accepted"), NamedTextColor.GRAY));
            }
            return;
        }
        double balance = credit.getOrDefault(player.getUniqueId(), 0.0) + total;
        int emeralds = (int) Math.floor(balance + 1e-9);
        balance -= emeralds;
        credit.put(player.getUniqueId(), balance);
        saveCredit(player.getUniqueId(), balance);
        for (int left = emeralds; left > 0; left -= 64) {
            give(player, new ItemStack(Material.EMERALD, Math.min(64, left)));
        }
        player.sendMessage(Component.text(Lang.tr("economy.buyer.sold", sold,
                String.format(Locale.ROOT, "%.2f", total), emeralds, String.format(Locale.ROOT, "%.2f", balance)),
                NamedTextColor.GREEN));
        if (!returned.isEmpty()) {
            player.sendMessage(Component.text(Lang.tr("economy.buyer.partly-returned"), NamedTextColor.GRAY));
        }
    }

    /** Price per unit; 0 means the buyer does not accept it. Damaged tools are cheaper in proportion to durability. */
    private double unitPrice(ItemStack item, HeartholdConfig c) {
        Material type = item.getType();
        if (type == Material.EMERALD || type == Material.EMERALD_BLOCK
                || cells.amount(item).isPresent() || baseItems.kindOf(item).isPresent() || hasContents(item)) {
            return 0;
        }
        double price = c.buyerPrices().get(type);
        if (item.getItemMeta() instanceof Damageable d && type.getMaxDurability() > 0 && d.hasDamage()) {
            price *= Math.max(0, 1.0 - (double) d.getDamage() / type.getMaxDurability());
        }
        return price;
    }

    /** A shulker box or bundle with contents is not bought: the items inside would be lost. */
    private static boolean hasContents(ItemStack item) {
        if (!item.hasItemMeta()) {
            return false;
        }
        if (item.getItemMeta() instanceof BundleMeta bundle) {
            return bundle.hasItems();
        }
        if (item.getItemMeta() instanceof BlockStateMeta bsm && bsm.hasBlockState()
                && bsm.getBlockState() instanceof ShulkerBox box) {
            return !box.getInventory().isEmpty();
        }
        return false;
    }

    private static void give(Player player, ItemStack item) {
        player.getInventory().addItem(item).values()
                .forEach(rest -> player.getWorld().dropItem(player.getLocation(), rest));
    }

    private void saveCredit(UUID player, double amount) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT OR REPLACE INTO buyer_credit (player_id, amount) VALUES (?, ?)")) {
            ps.setString(1, player.toString());
            ps.setDouble(2, amount);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to save buyer credit", e);
        }
    }
}
