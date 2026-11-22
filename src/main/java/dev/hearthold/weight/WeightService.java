package dev.hearthold.weight;

import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.config.WeightConfig;
import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.ChestedHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.BrewingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionType;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Weight of items, players and mounts; the carrying capacity potion. */
public final class WeightService {

    private final Supplier<HeartholdConfig> config;
    private final NamespacedKey potionKey;
    private final NamespacedKey bonusUntilKey;
    private final NamespacedKey bonusKey;
    private final NamespacedKey mixKey;
    /** Extra weight of special items (e.g. cargo in a suitcase). */
    private java.util.function.ToDoubleFunction<ItemStack> extraWeight = item -> 0;

    public WeightService(Plugin plugin, Supplier<HeartholdConfig> config) {
        this.config = config;
        this.potionKey = new NamespacedKey(plugin, "capacity_potion");
        this.bonusUntilKey = new NamespacedKey(plugin, "capacity_until");
        this.bonusKey = new NamespacedKey(plugin, "capacity_bonus");
        this.mixKey = new NamespacedKey(plugin, "capacity_potion_mix");
    }

    public void setExtraWeight(java.util.function.ToDoubleFunction<ItemStack> extra) {
        this.extraWeight = extra;
    }

    private WeightConfig w() {
        return config.get().weight();
    }

    /** Weight of a stack including shulker box or bundle contents. */
    public double weight(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return 0;
        }
        double total = w().itemWeights().get(item.getType()) * item.getAmount() + extraWeight.applyAsDouble(item);
        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof BundleMeta bundle) {
                for (ItemStack inner : bundle.getItems()) {
                    total += weight(inner);
                }
            } else if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState()
                    && bsm.getBlockState() instanceof ShulkerBox box) {
                for (ItemStack inner : box.getInventory().getContents()) {
                    total += weight(inner) * item.getAmount();
                }
            }
        }
        return total;
    }

    public double weight(ItemStack[] items) {
        double total = 0;
        for (ItemStack item : items) {
            total += weight(item);
        }
        return total;
    }

    /** Weight the player carries: inventory, off hand and (if enabled) worn armour. */
    public double weight(Player player) {
        PlayerInventory inv = player.getInventory();
        double total = weight(inv.getStorageContents()) + weight(inv.getItemInOffHand());
        if (w().countArmor()) {
            total += weight(inv.getArmorContents());
        }
        return total;
    }

    /** Cargo weight: material × amount. */
    public double weight(Material m, int amount) {
        return w().itemWeights().get(m) * amount;
    }

    /** Cargo weight in a donkey, mule or llama chest. */
    public double cargo(Entity entity) {
        if (entity instanceof ChestedHorse horse && horse.isCarryingChest()) {
            return weight(horse.getInventory().getContents());
        }
        return 0;
    }

    public double capacity(Player player) {
        return w().playerCapacity() + potionBonus(player);
    }

    public double potionBonus(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        Long until = pdc.get(bonusUntilKey, PersistentDataType.LONG);
        if (until == null || until <= System.currentTimeMillis()) {
            return 0;
        }
        return pdc.getOrDefault(bonusKey, PersistentDataType.DOUBLE, 0.0);
    }

    public long potionMinutesLeft(Player player) {
        Long until = player.getPersistentDataContainer().get(bonusUntilKey, PersistentDataType.LONG);
        return until == null ? 0 : Math.max(0, (until - System.currentTimeMillis() + 59_999) / 60_000);
    }

    // --- Carrying capacity potion ---

    public ItemStack createPotion() {
        WeightConfig c = w();
        ItemStack item = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) item.getItemMeta();
        meta.setColor(Color.fromRGB(0x8B5A2B));
        meta.displayName(Component.text(Lang.tr("weight.potion.name"), NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text(Lang.tr("weight.potion.lore", String.format(Locale.ROOT, "%.0f", c.potionBonus()),
                c.potionMinutes()), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(potionKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isPotion(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(potionKey, PersistentDataType.BYTE);
    }

    public void drink(Player player) {
        WeightConfig c = w();
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        pdc.set(bonusUntilKey, PersistentDataType.LONG, System.currentTimeMillis() + c.potionMinutes() * 60_000L);
        pdc.set(bonusKey, PersistentDataType.DOUBLE, c.potionBonus());
    }

    /** Brewing recipe: awkward potion + ingredient from config. Re-registered when the config reloads. */
    public void registerBrewing() {
        Bukkit.removeRecipe(mixKey);
        ItemStack awkward = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) awkward.getItemMeta();
        meta.setBasePotionType(PotionType.AWKWARD);
        awkward.setItemMeta(meta);
        Bukkit.addRecipe(new BrewingRecipe(mixKey, createPotion(), RecipeChoice.exactChoice(awkward),
                new RecipeChoice.MaterialChoice(w().potionIngredient())));
    }

    public void unregisterBrewing() {
        Bukkit.removeRecipe(mixKey);
    }
}
