package dev.hearthold.base;

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
import java.util.Optional;
import java.util.UUID;

/** Base heart, node and proxy items: ordinary blocks tagged with the sphere kind in the PersistentDataContainer. */
public final class BaseItems {

    private final NamespacedKey key;
    private final NamespacedKey patronKey;

    public BaseItems(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "base_sphere");
        this.patronKey = new NamespacedKey(plugin, "patron");
    }

    /** Patronage heart: the clan that places it becomes a vassal of the patron clan. */
    public ItemStack createPatronHeart(Material material, UUID patron, String patronName, double days) {
        ItemStack item = create(Sphere.Kind.HEART, material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(Lang.tr("base.item.patron-heart.name", patronName), NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new java.util.ArrayList<>(meta.lore());
        lore.add(Component.text(Lang.tr("base.item.patron-heart.lore", patronName,
                String.format(java.util.Locale.ROOT, "%.0f", days)),
                NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(patronKey, PersistentDataType.STRING, patron.toString());
        item.setItemMeta(meta);
        return item;
    }

    public Optional<UUID> patronOf(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) {
            return Optional.empty();
        }
        String v = item.getItemMeta().getPersistentDataContainer().get(patronKey, PersistentDataType.STRING);
        return v == null ? Optional.empty() : Optional.of(UUID.fromString(v));
    }

    public ItemStack create(Sphere.Kind kind, Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(kind.title(), NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        String hint = switch (kind) {
            case HEART -> Lang.tr("base.item.hint.heart");
            case NODE -> Lang.tr("base.item.hint.node");
            case PROXY -> Lang.tr("base.item.hint.proxy");
        };
        meta.lore(List.of(
                Component.text(hint, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text(Lang.tr("base.item.indestructible"), NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, kind.name());
        item.setItemMeta(meta);
        return item;
    }

    public Optional<Sphere.Kind> kindOf(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) {
            return Optional.empty();
        }
        String value = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Sphere.Kind.valueOf(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
