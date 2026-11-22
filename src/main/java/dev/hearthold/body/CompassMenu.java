package dev.hearthold.body;

import dev.hearthold.base.Sphere;
import dev.hearthold.base.TerritoryService;
import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanService;
import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Clan list in the compass: direction and distance to the heart, online players, prestige, reputation, status.
 * Clicking a clan points the compass at its heart.
 */
public final class CompassMenu implements Listener {

    private static final class Holder implements InventoryHolder {
        final List<Location> targets = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        java.util.function.Consumer<Player> back;
        Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final ClanService clans;
    private final TerritoryService territory;
    private final ToIntFunction<UUID> prestige;
    private final ToIntFunction<UUID> reputation;
    /** Special clan status (shield, newbie protection, vassal) or null. */
    private final Function<UUID, String> status;

    public CompassMenu(ClanService clans, TerritoryService territory, ToIntFunction<UUID> prestige,
                       ToIntFunction<UUID> reputation, Function<UUID, String> status) {
        this.clans = clans;
        this.territory = territory;
        this.prestige = prestige;
        this.reputation = reputation;
        this.status = status;
    }

    public void open(Player player) {
        open(player, null);
    }

    public void open(Player player, java.util.function.Consumer<Player> back) {
        Holder h = new Holder();
        h.back = back;
        h.inventory = Bukkit.createInventory(h, 54, Component.text(Lang.tr("compass.clans.title")));
        Location here = player.getLocation();
        record Row(Clan clan, Location heart, double distance) { }
        List<Row> rows = new ArrayList<>();
        for (Clan c : clans.all()) {
            Optional<Sphere> heart = territory.heartOf(c.id());
            if (heart.isEmpty()) {
                continue;
            }
            World w = Bukkit.getWorld(heart.get().world());
            if (w == null) {
                continue;
            }
            Location l = new Location(w, heart.get().x() + 0.5, heart.get().y(), heart.get().z() + 0.5);
            double d = w.equals(here.getWorld()) ? l.distance(here) : Double.MAX_VALUE;
            rows.add(new Row(c, l, d));
        }
        rows.sort(Comparator.comparingDouble(Row::distance));
        for (int i = 0; i < Math.min(45, rows.size()); i++) {
            Row r = rows.get(i);
            long online = r.clan().members().keySet().stream().filter(id -> Bukkit.getPlayer(id) != null).count();
            List<Component> lore = new ArrayList<>();
            lore.add(line(r.distance() == Double.MAX_VALUE ? Lang.tr("compass.clans.other-world")
                    : Lang.tr("compass.clans.path", direction(here, r.heart()), (int) r.distance()), NamedTextColor.GRAY));
            lore.add(line(Lang.tr("compass.clans.players", online, r.clan().members().size()), NamedTextColor.GRAY));
            lore.add(line(Lang.tr("compass.clans.prestige", prestige.applyAsInt(r.clan().id()),
                    reputation.applyAsInt(r.clan().id())), NamedTextColor.GRAY));
            String st = status.apply(r.clan().id());
            if (st != null) {
                lore.add(line(st, NamedTextColor.AQUA));
            }
            lore.add(line(Lang.tr("compass.clans.click"), NamedTextColor.DARK_GRAY));
            ItemStack icon = new ItemStack(Material.WHITE_BANNER);
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(line(r.clan().name(), NamedTextColor.GOLD));
            meta.lore(lore);
            icon.setItemMeta(meta);
            h.inventory.setItem(i, icon);
            h.targets.add(r.heart());
            h.names.add(r.clan().name());
        }
        if (back != null) {
            ItemStack arrow = new ItemStack(Material.ARROW);
            ItemMeta am = arrow.getItemMeta();
            am.displayName(line(Lang.tr("compass.back"), NamedTextColor.GOLD));
            arrow.setItemMeta(am);
            h.inventory.setItem(53, arrow);
        }
        player.openInventory(h.inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder h)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getSlot();
        if (slot == 53 && h.back != null) {
            h.back.accept(player);
            return;
        }
        if (slot < h.targets.size()) {
            player.setCompassTarget(h.targets.get(slot));
            player.closeInventory();
            player.sendActionBar(Component.text(Lang.tr("compass.clans.pointed", h.names.get(slot)),
                    NamedTextColor.GOLD));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    /** Cardinal direction; north is minus Z. */
    private static String direction(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        if (angle < 0) {
            angle += 360;
        }
        String[] keys = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};
        return Lang.tr("compass.dir." + keys[(int) Math.round(angle / 45) % 8]);
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
