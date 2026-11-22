package dev.hearthold.trade;

import dev.hearthold.clan.Clan;
import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Treasury window. Shift-click an own item below to deposit it (any member).
 * Click a treasury item to take a stack, right-click to take one (leader and trusted only).
 */
public final class TreasuryMenu implements Listener {

    private static final int PAGE = 45;

    private static final class Holder implements InventoryHolder {
        final Clan clan;
        int page;
        final List<Material> shown = new ArrayList<>();
        Inventory inventory;

        Holder(Clan clan, int page) {
            this.clan = clan;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Treasury treasury;
    private final CaravanLog log;

    public TreasuryMenu(Treasury treasury, CaravanLog log) {
        this.treasury = treasury;
        this.log = log;
    }

    public void open(Player player, Clan clan) {
        Holder h = new Holder(clan, 0);
        h.inventory = Bukkit.createInventory(h, 54, Component.text(Lang.tr("trade.treasury.title", clan.name())));
        render(h);
        player.openInventory(h.inventory);
    }

    private void render(Holder h) {
        Inventory inv = h.inventory;
        inv.clear();
        h.shown.clear();
        List<Map.Entry<Material, Integer>> entries = new ArrayList<>(treasury.contents(h.clan.id()).entrySet());
        int pages = Math.max(1, (entries.size() + PAGE - 1) / PAGE);
        h.page = Math.min(h.page, pages - 1);
        int from = h.page * PAGE;
        for (int i = 0; i < PAGE && from + i < entries.size(); i++) {
            var e = entries.get(from + i);
            ItemStack icon = new ItemStack(e.getKey().isItem() ? e.getKey() : Material.BARRIER,
                    Math.max(1, Math.min(e.getValue(), e.getKey().getMaxStackSize())));
            ItemMeta meta = icon.getItemMeta();
            meta.lore(List.of(plain(Lang.tr("trade.treasury.amount", e.getValue()), NamedTextColor.YELLOW),
                    plain(Lang.tr("trade.treasury.click-hint"), NamedTextColor.DARK_GRAY)));
            icon.setItemMeta(meta);
            inv.setItem(i, icon);
            h.shown.add(e.getKey());
        }
        if (h.page > 0) {
            inv.setItem(45, button(Material.ARROW, Lang.tr("trade.treasury.prev")));
        }
        inv.setItem(49, button(Material.BOOK, Lang.tr("trade.treasury.page", h.page + 1, pages)));
        if (h.page < pages - 1) {
            inv.setItem(53, button(Material.ARROW, Lang.tr("trade.treasury.next")));
        }
    }

    private static ItemStack button(Material m, String name) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(name, NamedTextColor.GOLD));
        item.setItemMeta(meta);
        return item;
    }

    private static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder h)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        boolean top = event.getClickedInventory() == event.getView().getTopInventory();
        UUID clanId = h.clan.id();
        if (!top) {
            if (!event.isShiftClick()) {
                return;
            }
            ItemStack item = event.getCurrentItem();
            if (item == null || item.isEmpty()) {
                return;
            }
            if (!Treasury.accepts(item)) {
                player.sendActionBar(Component.text(Lang.tr("trade.treasury.simple-only"),
                        NamedTextColor.RED));
                return;
            }
            treasury.add(clanId, item.getType(), item.getAmount());
            log.add(clanId, player.getName(), Lang.tr("trade.log.deposit"), item.getAmount() + " " + item.getType().name());
            event.setCurrentItem(null);
            render(h);
            return;
        }
        int slot = event.getSlot();
        if (slot == 45 && h.page > 0) {
            h.page--;
            render(h);
            return;
        }
        if (slot == 53) {
            h.page++;
            render(h);
            return;
        }
        if (slot >= PAGE || slot >= h.shown.size()) {
            return;
        }
        if (!h.clan.canTrade(player.getUniqueId())) {
            player.sendActionBar(Component.text(Lang.tr("trade.treasury.no-rights"), NamedTextColor.RED));
            return;
        }
        Material m = h.shown.get(slot);
        int want = event.getClick() == ClickType.RIGHT ? 1 : m.getMaxStackSize();
        int taken = treasury.take(clanId, m, want);
        if (taken > 0) {
            int left = player.getInventory().addItem(new ItemStack(m, taken)).values().stream()
                    .mapToInt(ItemStack::getAmount).sum();
            treasury.add(clanId, m, left);
            if (taken - left > 0) {
                log.add(clanId, player.getName(), Lang.tr("trade.log.withdraw"), (taken - left) + " " + m.name());
            }
        }
        render(h);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }
}
