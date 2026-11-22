package dev.hearthold.economy;

import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

/** Energy cell: carries energy from the base heart to a chunk. The amount is stored in the PersistentDataContainer. */
public final class EnergyCell {

    private final NamespacedKey key;

    public EnergyCell(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "energy_cell");
    }

    public ItemStack create(Material material, double amount) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(Lang.tr("economy.cell.name"), NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(Lang.tr("economy.cell.energy", String.format(Locale.ROOT, "%.0f", amount)), NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text(Lang.tr("economy.cell.hint"), NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(key, PersistentDataType.DOUBLE, amount);
        item.setItemMeta(meta);
        return item;
    }

    public OptionalDouble amount(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) {
            return OptionalDouble.empty();
        }
        Double v = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.DOUBLE);
        return v == null ? OptionalDouble.empty() : OptionalDouble.of(v);
    }
}
