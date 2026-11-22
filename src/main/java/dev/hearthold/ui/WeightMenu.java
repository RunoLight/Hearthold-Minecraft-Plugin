package dev.hearthold.ui;

import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.config.WeightConfig;
import dev.hearthold.i18n.Lang;
import dev.hearthold.weight.WeightService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Detailed weight breakdown: total and thresholds, then each kind of cargo with its share of the total. */
public final class WeightMenu {

    private record Group(Material icon, String name, int amount, double weight) { }

    private final WeightService weights;
    private final Supplier<HeartholdConfig> config;

    public WeightMenu(WeightService weights, Supplier<HeartholdConfig> config) {
        this.weights = weights;
        this.config = config;
    }

    public void open(Player player, Consumer<Player> back) {
        WeightConfig w = config.get().weight();
        PlayerInventory inv = player.getInventory();

        // Group by material; special items (suitcases etc.) are grouped separately by name.
        Map<String, Group> groups = new LinkedHashMap<>();
        List<ItemStack> carried = new ArrayList<>();
        for (ItemStack i : inv.getStorageContents()) {
            carried.add(i);
        }
        carried.add(inv.getItemInOffHand());
        boolean armor = w.countArmor();
        if (armor) {
            carried.addAll(java.util.Arrays.asList(inv.getArmorContents())); // empty slots = null
        }
        for (ItemStack item : carried) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            double weight = weights.weight(item);
            boolean special = item.hasItemMeta() && item.getItemMeta().hasDisplayName();
            String key = special ? "special:" + item.getType() + ":" + groups.size() : item.getType().name();
            String name = special
                    ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(item.getItemMeta().displayName())
                    : item.getType().name().toLowerCase(Locale.ROOT);
            groups.merge(key, new Group(item.getType(), name, item.getAmount(), weight),
                    (a, b) -> new Group(a.icon(), a.name(), a.amount() + b.amount(), a.weight() + b.weight()));
        }
        double load = groups.values().stream().mapToDouble(Group::weight).sum();
        double cap = weights.capacity(player);
        double potion = weights.potionBonus(player);

        Menu menu = new Menu(6, Component.text(Lang.tr("ui.weight.title")));
        List<String> summary = new ArrayList<>();
        summary.add(Lang.tr("ui.weight.carrying", String.format(Locale.ROOT, "%.2f", load), String.format(Locale.ROOT, "%.1f", cap)));
        summary.add(Lang.tr("ui.weight.free-until", String.format(Locale.ROOT, "%.1f", cap * w.slowTier())));
        summary.add(Lang.tr("ui.weight.slow-after", String.format(Locale.ROOT, "%.1f", cap * w.slowTier())));
        summary.add(Lang.tr("ui.weight.no-sprint-after", String.format(Locale.ROOT, "%.1f", cap * w.heavyTier())));
        summary.add(Lang.tr("ui.weight.stop-after", String.format(Locale.ROOT, "%.1f", cap * w.stopTier())));
        if (potion > 0) {
            summary.add(Lang.tr("ui.weight.potion", String.format(Locale.ROOT, "%.0f", potion), weights.potionMinutesLeft(player)));
        }
        summary.add(Lang.tr(armor ? "ui.weight.armor-counted" : "ui.weight.armor-not-counted"));
        menu.set(4, Menu.icon(Material.ANVIL, Lang.tr("ui.weight.total"), summary), null);

        Entity vehicle = player.getVehicle();
        if (vehicle != null && w.mountCapacity().containsKey(vehicle.getType())) {
            double cargo = weights.cargo(vehicle);
            menu.set(0, Menu.icon(Material.SADDLE, Lang.tr("ui.weight.mount"),
                    Lang.tr("ui.weight.mount.cargo", String.format(Locale.ROOT, "%.2f", cargo)),
                    Lang.tr("ui.weight.mount.capacity", String.format(Locale.ROOT, "%.0f", w.mountCapacity().get(vehicle.getType()))),
                    Lang.tr("ui.weight.mount.combined", String.format(Locale.ROOT, "%.2f", load + cargo),
                            String.format(Locale.ROOT, "%.1f", cap + w.mountCapacity().get(vehicle.getType())))), null);
        }

        List<Group> sorted = new ArrayList<>(groups.values());
        sorted.sort((a, b) -> Double.compare(b.weight(), a.weight()));
        int slot = 9;
        for (Group g : sorted) {
            if (slot >= 53) {
                break;
            }
            double share = load > 0 ? g.weight() / load * 100 : 0;
            menu.set(slot++, Menu.icon(g.icon(), g.amount(), g.name(),
                    Lang.tr("ui.weight.amount", g.amount()),
                    Lang.tr("ui.weight.group-weight", String.format(Locale.ROOT, "%.3f", g.weight()), String.format(Locale.ROOT, "%.0f", share)),
                    Lang.tr("ui.weight.per-item", String.format(Locale.ROOT, "%.4f", g.weight() / Math.max(1, g.amount())))), null);
        }
        if (sorted.isEmpty()) {
            menu.set(22, Menu.icon(Material.FEATHER, Lang.tr("ui.weight.empty")), null);
        }
        menu.set(53, Menu.icon(Material.ARROW, Lang.tr("ui.back")), back);
        menu.open(player);
    }
}
