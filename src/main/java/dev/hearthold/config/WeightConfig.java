package dev.hearthold.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Weight and logistics. Weight is measured in load units (by default a stack of ordinary blocks = 1).
 * Overload thresholds are fractions of capacity: at capacity × tier, slowdown and so on.
 */
public record WeightConfig(
        boolean enabled,
        MaterialValues itemWeights,
        boolean countArmor,
        double playerCapacity,
        double slowTier,
        double heavyTier,
        double stopTier,
        double slowSpeed,
        double heavySpeed,
        Map<EntityType, Double> mountCapacity,
        double potionBonus,
        int potionMinutes,
        Material potionIngredient,
        MaterialSet storageAllowed,
        boolean storageAllowFood
) {

    public enum Tier {
        NONE, SLOW, HEAVY, STOP
    }

    /** Overload tier for a given load and capacity. */
    public Tier tier(double load, double capacity) {
        if (load > capacity * stopTier) {
            return Tier.STOP;
        }
        if (load > capacity * heavyTier) {
            return Tier.HEAVY;
        }
        if (load > capacity * slowTier) {
            return Tier.SLOW;
        }
        return Tier.NONE;
    }

    /** Speed multiplier for a tier: 1 means no penalty, 0 means standing still. */
    public double speedFactor(Tier tier) {
        return switch (tier) {
            case NONE -> 1.0;
            case SLOW -> slowSpeed;
            case HEAVY -> heavySpeed;
            case STOP -> 0.0;
        };
    }

    public boolean storageAllows(Material m) {
        return storageAllowed.contains(m) || (storageAllowFood && m.isEdible());
    }

    static WeightConfig load(FileConfiguration c, Logger log) {
        Map<EntityType, Double> mounts = new EnumMap<>(EntityType.class);
        ConfigurationSection ms = c.getConfigurationSection("weight.mounts");
        if (ms != null) {
            for (String key : ms.getKeys(false)) {
                try {
                    mounts.put(EntityType.valueOf(key.toUpperCase(Locale.ROOT)), Math.max(0.0, ms.getDouble(key)));
                } catch (IllegalArgumentException e) {
                    log.warning("Unknown mount in weight.mounts: " + key);
                }
            }
        }
        Material ingredient = Material.matchMaterial(c.getString("weight.potion.ingredient", "IRON_INGOT"));
        List<String> allowed = c.getStringList("weight.storage.allowed");
        return new WeightConfig(
                c.getBoolean("weight.enabled", true),
                MaterialValues.parse(c.getConfigurationSection("weight.items"),
                        Math.max(0.0, c.getDouble("weight.default-item", 1.0 / 256)), "weight.items", log),
                c.getBoolean("weight.count-armor", true),
                Math.max(0.1, c.getDouble("weight.player-capacity", 10)),
                Math.max(0.0, c.getDouble("weight.tiers.slow", 1.0)),
                Math.max(0.0, c.getDouble("weight.tiers.heavy", 1.4)),
                Math.max(0.0, c.getDouble("weight.tiers.stop", 1.8)),
                clamp01(c.getDouble("weight.speed.slow", 0.75)),
                clamp01(c.getDouble("weight.speed.heavy", 0.5)),
                Map.copyOf(mounts),
                Math.max(0.0, c.getDouble("weight.potion.bonus", 5)),
                Math.max(1, c.getInt("weight.potion.minutes", 8)),
                ingredient == null ? Material.IRON_INGOT : ingredient,
                MaterialSet.parse(allowed, "weight.storage.allowed", log),
                c.getBoolean("weight.storage.allow-food", true));
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
