package dev.hearthold.trade;

import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Suitcase: packed caravan cargo. It records the route, sender, recipient and cargo.
 * It cannot be dropped or put into a container; only a player outside the sender clan can open it.
 */
public final class Suitcase {

    /** Suitcase data. */
    public record Data(UUID caravanId, UUID routeId, UUID fromClan, UUID toClan, String courier,
                       Map<Material, Integer> cargo, boolean leftBase) { }

    private final NamespacedKey caravanKey, routeKey, fromKey, toKey, courierKey, cargoKey, leftKey;

    public Suitcase(Plugin plugin) {
        caravanKey = new NamespacedKey(plugin, "suitcase");
        routeKey = new NamespacedKey(plugin, "suitcase_route");
        fromKey = new NamespacedKey(plugin, "suitcase_from");
        toKey = new NamespacedKey(plugin, "suitcase_to");
        courierKey = new NamespacedKey(plugin, "suitcase_courier");
        cargoKey = new NamespacedKey(plugin, "suitcase_cargo");
        leftKey = new NamespacedKey(plugin, "suitcase_left");
    }

    public ItemStack create(Data d, String fromName, String toName, String routeShort) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(NamespacedKey.minecraft("chest"));
        meta.setMaxStackSize(1);
        meta.displayName(Component.text(Lang.tr("trade.suitcase.name", fromName, toName), NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(gray(Lang.tr("trade.suitcase.route", routeShort, d.courier())));
        d.cargo().forEach((m, a) -> lore.add(gray("  " + a + " × " + m.name().toLowerCase(Locale.ROOT))));
        lore.add(gray(Lang.tr("trade.suitcase.deliver-hint")));
        lore.add(gray(Lang.tr("trade.suitcase.cancel-hint")));
        meta.lore(lore);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(caravanKey, PersistentDataType.STRING, d.caravanId().toString());
        pdc.set(routeKey, PersistentDataType.STRING, d.routeId().toString());
        pdc.set(fromKey, PersistentDataType.STRING, d.fromClan().toString());
        pdc.set(toKey, PersistentDataType.STRING, d.toClan().toString());
        pdc.set(courierKey, PersistentDataType.STRING, d.courier());
        pdc.set(cargoKey, PersistentDataType.STRING, encode(d.cargo()));
        pdc.set(leftKey, PersistentDataType.BYTE, (byte) (d.leftBase() ? 1 : 0));
        item.setItemMeta(meta);
        return item;
    }

    public boolean is(ItemStack item) {
        return item != null && !item.isEmpty() && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(caravanKey, PersistentDataType.STRING);
    }

    public Optional<Data> read(ItemStack item) {
        if (!is(item)) {
            return Optional.empty();
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        try {
            return Optional.of(new Data(
                    UUID.fromString(pdc.get(caravanKey, PersistentDataType.STRING)),
                    UUID.fromString(pdc.get(routeKey, PersistentDataType.STRING)),
                    UUID.fromString(pdc.get(fromKey, PersistentDataType.STRING)),
                    UUID.fromString(pdc.get(toKey, PersistentDataType.STRING)),
                    pdc.getOrDefault(courierKey, PersistentDataType.STRING, "?"),
                    decode(pdc.getOrDefault(cargoKey, PersistentDataType.STRING, "")),
                    pdc.getOrDefault(leftKey, PersistentDataType.BYTE, (byte) 0) == 1));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Marks that the courier has left their own base (cancellation is possible after that). */
    public void markLeft(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(leftKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
    }

    public static String encode(Map<Material, Integer> cargo) {
        StringBuilder sb = new StringBuilder();
        cargo.forEach((m, a) -> sb.append(sb.isEmpty() ? "" : ",").append(m.name()).append(':').append(a));
        return sb.toString();
    }

    public static Map<Material, Integer> decode(String s) {
        Map<Material, Integer> result = new LinkedHashMap<>();
        if (s.isBlank()) {
            return result;
        }
        for (String part : s.split(",")) {
            String[] kv = part.split(":");
            Material m = Material.matchMaterial(kv[0]);
            if (m != null && kv.length == 2) {
                result.merge(m, Integer.parseInt(kv[1]), Integer::sum);
            }
        }
        return result;
    }

    private static Component gray(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }
}
