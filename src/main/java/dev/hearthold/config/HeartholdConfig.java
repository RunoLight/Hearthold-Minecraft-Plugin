package dev.hearthold.config;

import dev.hearthold.base.Sphere;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.potion.PotionEffectType;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** Immutable snapshot of config.yml. Rebuilt on /hearthold reload. */
public record HeartholdConfig(
        SphereSettings heart,
        SphereSettings node,
        int maxNodes,
        int alertRadius,
        List<Area> neutralZones,
        int protectedMaxHp,
        double regenPerSecond,
        int regenDelaySeconds,
        double durabilityMultiplier,
        int displaySeconds,
        Map<String, Integer> toolDamage,
        MaterialValues wildHpTable,
        WildFormula wildFormula,
        double wildDurabilityMultiplier,
        PrimeTime primeTime,
        Map<PotionEffectType, Integer> defenderEffects,
        boolean defenderBonusForAllies,
        BuildSettings build,
        UpkeepSettings upkeep,
        EnergySettings energy,
        MaterialValues buyerPrices,
        double deathDropFraction,
        MapConfig map,
        WeightConfig weight,
        TradeSettings trade,
        WarSettings war,
        CraftSettings craft,
        PlotSettings plot,
        NewbieSettings newbie,
        EventConfig events,
        WebSettings web,
        boolean siegeRequiresWar,
        List<BaseLevel> baseLevels,
        double nodeCost,
        /** How many times faster tools wear against a foreign base outside prime time. */
        double offPrimeWear,
        /** After how many days the removal timer deletes a base heart. */
        double heartRemovalDays
) {

    /**
     * Base level: HP of a protected block, the cost of upgrading to this level (emeralds from the heart's
     * treasury) and the extra daily upkeep. Every base starts at level 1.
     */
    public record BaseLevel(int level, String name, int hp, double upgradeCost, double upkeepPerDay) { }

    /** Base level by number (1…); out of range returns the nearest one. */
    public BaseLevel baseLevel(int level) {
        return baseLevels.get(Math.max(0, Math.min(level, baseLevels.size()) - 1));
    }

    /**
     * Web portal. Scouting: level 0 — no players; 1 — players outside bases, delayed; 2 — plus couriers with load;
     * 3 — shorter delay. The level's cost is added to the clan's upkeep.
     */
    public record WebSettings(boolean enabled, String bind, int port, String publicUrl, String world,
                              int tokenMinutes, int sessionDays, List<Double> scoutingCost,
                              int slowDelaySeconds, int fastDelaySeconds) {

        public double costOf(int tier) {
            return tier <= 0 || scoutingCost.isEmpty() ? 0 : scoutingCost.get(Math.min(tier, scoutingCost.size()) - 1);
        }
    }

    /** First-base protection, heart and proxy costs. */
    public record NewbieSettings(double days, int radius, int maxContainers, double firstHeartCost, double heartCost,
                                 SphereSettings proxy, double proxyCost, double proxyPerDay, int maxProxies) { }

    /** Plots and building valuation. */
    public record PlotSettings(Material material, int maxSize, int maxPerClan, double rateCooldownHours,
                               MaterialValues blockValues, int prestigePlots, double energyPerPrestige) { }

    /** Timed crafting and per-clan station limits (keys: enchanting, anvil, brewing). */
    public record CraftSettings(double enchantMinutesPerLevel, double anvilMinutesPerLevel,
                                double foodMultiplier, double smeltMultiplier, double brewMultiplier,
                                Map<String, Integer> stationLimits, boolean stationsOnlyInBase) { }

    /** Wars, fatigue, shield, heart capture, vassalage, patronage. */
    public record WarSettings(
            double durationHours, int prepareMinutes,
            double fatiguePerWar, double fatigueDecayDays, double fatigueUpkeep, double fatigueRegen,
            double shieldHours,
            int captureRadius, int captureSeconds, double captureDecay,
            double vassalDays, double tributeCollectors, double tributeTrade,
            double patronageDays, double patronageCost, int patronageMax) { }

    /** Trade routes: how long to stand for delivery/cancel/return and the horse-to-wagon upgrade cost. */
    public record TradeSettings(int standSeconds, Map<Material, Integer> wagonCost) { }


    public record SphereSettings(int radius, Material material, int activationMinutes) { }

    /**
     * Wilderness block HP from vanilla hardness: hardness × multiplier, clamped to min-hp..max-hp.
     * Blocks softer than soft-hardness (grass, flowers, leaves, torches) break as in vanilla.
     */
    public record WildFormula(double multiplier, int minHp, int maxHp, double softHardness) { }

    /** Block placement time in seconds per zone; 0 means instant, as in vanilla. */
    public record BuildSettings(double wildSeconds, double ownBaseSeconds, double warBaseSeconds,
                                boolean onlySolid, MaterialSet instantBlocks) { }

    /** Upkeep cost in emeralds per day. */
    public record UpkeepSettings(boolean enabled, double heartPerDay, double nodePerDay, double startBalance,
                                 double warnDays) { }

    public record FertileZone(Area area, double multiplier) { }

    public record EnergySettings(
            double heartBasePerHour, double perMemberPerHour, double perNodePerHour, double heartMax,
            double cellSize, Material cellMaterial,
            double chunkNaturalMax, double chunkRegenPerHour, double chunkMax,
            double costCropStage, double costTree, double costBonemeal, double costBreed,
            List<FertileZone> fertileZones) {

        /** Fertility multiplier for a point (the highest of the zones containing it), otherwise 1. */
        public double fertility(String world, int x, int z) {
            double m = 1.0;
            for (FertileZone f : fertileZones) {
                if (f.area().containsXZ(world, x, z)) {
                    m = Math.max(m, f.multiplier());
                }
            }
            return m;
        }
    }

    public SphereSettings sphere(Sphere.Kind kind) {
        return switch (kind) {
            case HEART -> heart;
            case NODE -> node;
            case PROXY -> newbie.proxy();
        };
    }

    public static HeartholdConfig load(FileConfiguration c, Logger log) {
        Map<String, Integer> toolDamage = new HashMap<>();
        ConfigurationSection tools = c.getConfigurationSection("block-health.tool-damage");
        if (tools != null) {
            for (String key : tools.getKeys(false)) {
                toolDamage.put(key.toLowerCase(Locale.ROOT), Math.max(1, tools.getInt(key)));
            }
        }
        toolDamage.putIfAbsent("hand", 1);

        Map<PotionEffectType, Integer> effects = new LinkedHashMap<>();
        ConfigurationSection eff = c.getConfigurationSection("defender-bonus.effects");
        if (eff != null) {
            for (String key : eff.getKeys(false)) {
                PotionEffectType type = Registry.MOB_EFFECT.get(NamespacedKey.minecraft(key.toLowerCase(Locale.ROOT)));
                if (type == null) {
                    log.warning("Unknown effect in defender-bonus.effects: " + key);
                } else {
                    effects.put(type, Math.max(0, eff.getInt(key)));
                }
            }
        }

        List<FertileZone> fertile = new ArrayList<>();
        for (Map<?, ?> raw : c.getMapList("energy.fertile-zones")) {
            Area area = area(raw, log);
            if (area != null) {
                Object mult = raw.get("multiplier");
                fertile.add(new FertileZone(area, mult instanceof Number n ? Math.max(0.0, n.doubleValue()) : 2.0));
            }
        }

        return new HeartholdConfig(
                sphere(c, "heart", 32, Material.LODESTONE, 30),
                sphere(c, "node", 16, Material.GILDED_BLACKSTONE, 10),
                Math.max(0, c.getInt("node.max-per-clan", 4)),
                Math.max(0, c.getInt("territory.alert-radius", 256)),
                areas(c.getMapList("neutral-zones"), log),
                Math.max(1, c.getInt("block-health.protected-max-hp", 100)),
                Math.max(0.0, c.getDouble("block-health.regen-per-second", 0.5)),
                Math.max(0, c.getInt("block-health.regen-delay-seconds", 30)),
                Math.max(0.0, c.getDouble("block-health.durability-multiplier", 1.0)),
                Math.max(1, c.getInt("block-health.display-seconds", 4)),
                Map.copyOf(toolDamage),
                MaterialValues.parse(c.getConfigurationSection("wilderness.blocks"),
                        Math.max(1, c.getInt("wilderness.default-hp", 1)), "wilderness.blocks", log),
                new WildFormula(
                        Math.max(0, c.getDouble("wilderness.hardness-multiplier", 4)),
                        Math.max(1, c.getInt("wilderness.min-hp", 2)),
                        Math.max(1, c.getInt("wilderness.max-hp", 60)),
                        Math.max(0, c.getDouble("wilderness.soft-hardness", 0.25))),
                Math.max(0.0, c.getDouble("wilderness.durability-multiplier", 0.0)),
                primeTime(c, log),
                Map.copyOf(effects),
                c.getBoolean("defender-bonus.allies", true),
                new BuildSettings(
                        Math.max(0.0, c.getDouble("build.wild-seconds", 4.0)),
                        Math.max(0.0, c.getDouble("build.own-base-seconds", 0.0)),
                        Math.max(0.0, c.getDouble("build.war-base-seconds", 8.0)),
                        c.getBoolean("build.only-solid", true),
                        MaterialSet.parse(c.getStringList("build.instant-blocks"), "build.instant-blocks", log)),
                new UpkeepSettings(
                        c.getBoolean("upkeep.enabled", true),
                        Math.max(0.0, c.getDouble("upkeep.heart-per-day", 8)),
                        Math.max(0.0, c.getDouble("upkeep.node-per-day", 4)),
                        Math.max(0.0, c.getDouble("upkeep.start-balance", 0)),
                        Math.max(0.0, c.getDouble("upkeep.warn-days", 1))),
                new EnergySettings(
                        Math.max(0.0, c.getDouble("energy.heart.base-per-hour", 20)),
                        Math.max(0.0, c.getDouble("energy.heart.per-member-per-hour", 5)),
                        Math.max(0.0, c.getDouble("energy.heart.per-node-per-hour", 10)),
                        Math.max(0.0, c.getDouble("energy.heart.max", 2000)),
                        Math.max(1.0, c.getDouble("energy.cell.size", 100)),
                        material(c.getString("energy.cell.material"), Material.ECHO_SHARD),
                        Math.max(0.0, c.getDouble("energy.chunk.natural-max", 20)),
                        Math.max(0.0, c.getDouble("energy.chunk.regen-per-hour", 10)),
                        Math.max(0.0, c.getDouble("energy.chunk.max", 1000)),
                        Math.max(0.0, c.getDouble("energy.cost.crop-stage", 1)),
                        Math.max(0.0, c.getDouble("energy.cost.tree", 5)),
                        Math.max(0.0, c.getDouble("energy.cost.bonemeal", 2)),
                        Math.max(0.0, c.getDouble("energy.cost.breed", 10)),
                        List.copyOf(fertile)),
                MaterialValues.parse(c.getConfigurationSection("buyer.prices"),
                        Math.max(0.0, c.getDouble("buyer.default-price", 0.01)), "buyer.prices", log),
                Math.min(1.0, Math.max(0.0, c.getDouble("death.drop-fraction", 0.5))),
                MapConfig.load(c, log),
                WeightConfig.load(c, log),
                new TradeSettings(Math.max(1, c.getInt("trade.stand-seconds", 10)), wagonCost(c, log)),
                new WarSettings(
                        Math.max(1, c.getDouble("war.duration-hours", 72)),
                        Math.max(0, c.getInt("war.prepare-minutes", 0)),
                        Math.max(0, c.getDouble("war.fatigue.per-war", 1)),
                        Math.max(0.1, c.getDouble("war.fatigue.decay-days", 7)),
                        Math.max(0, c.getDouble("war.fatigue.upkeep-per-point", 0.25)),
                        Math.max(0, c.getDouble("war.fatigue.regen-slowdown-per-point", 0.25)),
                        Math.max(0, c.getDouble("war.shield-hours", 48)),
                        Math.max(1, c.getInt("war.capture.radius", 3)),
                        Math.max(1, c.getInt("war.capture.seconds", 180)),
                        Math.max(0, c.getDouble("war.capture.decay-per-second", 1)),
                        Math.max(0.01, c.getDouble("vassalage.days", 14)),
                        clamp(c.getDouble("vassalage.tribute.collectors", 0.2)),
                        clamp(c.getDouble("vassalage.tribute.trade", 0.2)),
                        Math.max(0.01, c.getDouble("patronage.days", 14)),
                        Math.max(0, c.getDouble("patronage.cost-emeralds", 16)),
                        Math.max(0, c.getInt("patronage.max-vassals", 2))),
                new CraftSettings(
                        Math.max(0, c.getDouble("craft.enchant-minutes-per-level", 2)),
                        Math.max(0, c.getDouble("craft.anvil-minutes-per-level", 2)),
                        Math.max(0.05, c.getDouble("craft.cooking.food-multiplier", 4)),
                        Math.max(0.05, c.getDouble("craft.cooking.smelt-multiplier", 1)),
                        Math.max(0.05, c.getDouble("craft.brewing-multiplier", 4)),
                        Map.of(
                                "enchanting", Math.max(0, c.getInt("craft.station-limits.enchanting", 2)),
                                "anvil", Math.max(0, c.getInt("craft.station-limits.anvil", 2)),
                                "brewing", Math.max(0, c.getInt("craft.station-limits.brewing", 3))),
                        c.getBoolean("craft.stations-only-in-base", true)),
                new PlotSettings(
                        material(c.getString("plot.material"), Material.TARGET),
                        Math.max(3, c.getInt("plot.max-size", 48)),
                        Math.max(0, c.getInt("plot.max-per-clan", 20)),
                        Math.max(0, c.getDouble("plot.rate-cooldown-hours", 24)),
                        MaterialValues.parse(c.getConfigurationSection("plot.block-values"),
                                Math.max(0, c.getDouble("plot.default-block-value", 1)), "plot.block-values", log),
                        Math.max(1, c.getInt("plot.prestige-plots", 5)),
                        Math.max(0, c.getDouble("plot.energy-per-prestige", 1))),
                new NewbieSettings(
                        Math.max(0, c.getDouble("newbie.days", 5)),
                        Math.max(1, c.getInt("newbie.radius", 16)),
                        Math.max(0, c.getInt("newbie.max-containers", 6)),
                        Math.max(0, c.getDouble("newbie.first-heart-cost", 0)),
                        Math.max(0, c.getDouble("heart.cost-emeralds", 32)),
                        sphere(c, "proxy", 16, Material.CRYING_OBSIDIAN, 15),
                        Math.max(0, c.getDouble("proxy.cost-emeralds", 64)),
                        Math.max(0, c.getDouble("proxy.per-day", 6)),
                        Math.max(0, c.getInt("proxy.max-per-clan", 2))),
                EventConfig.load(c, log),
                new WebSettings(
                        c.getBoolean("web.enabled", true),
                        c.getString("web.bind", "0.0.0.0"),
                        c.getInt("web.port", 8124),
                        c.getString("web.public-url", "http://localhost:8124").replaceAll("/+$", ""),
                        c.getString("web.world", "world"),
                        Math.max(1, c.getInt("web.token-minutes", 5)),
                        Math.max(1, c.getInt("web.session-days", 7)),
                        c.getDoubleList("web.scouting.cost-per-day").isEmpty() ? List.of(4.0, 8.0, 16.0)
                                : List.copyOf(c.getDoubleList("web.scouting.cost-per-day")),
                        Math.max(0, c.getInt("web.scouting.slow-delay-seconds", 60)),
                        Math.max(0, c.getInt("web.scouting.fast-delay-seconds", 5))),
                c.getBoolean("siege.require-war", true),
                baseLevels(c, Math.max(1, c.getInt("block-health.protected-max-hp", 100))),
                Math.max(0, c.getDouble("node.cost-emeralds", 16)),
                Math.max(1.0, c.getDouble("prime-time.off-prime-wear", 10.0)),
                Math.max(0.0, c.getDouble("upkeep.heart-removal-days", 7.0))
        );
    }

    private static SphereSettings sphere(FileConfiguration c, String path, int radius, Material material, int minutes) {
        Material m = material(c.getString(path + ".material"), material);
        if (!m.isBlock()) {
            m = material;
        }
        return new SphereSettings(
                Math.max(1, c.getInt(path + ".radius", radius)),
                m,
                Math.max(0, c.getInt(path + ".activation-minutes", minutes)));
    }

    private static Map<Material, Integer> wagonCost(FileConfiguration c, Logger log) {
        Map<Material, Integer> cost = new LinkedHashMap<>();
        ConfigurationSection s = c.getConfigurationSection("trade.wagon-cost");
        if (s != null) {
            for (String key : s.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                if (m == null) {
                    log.warning("Unknown material in trade.wagon-cost: " + key);
                } else {
                    cost.put(m, Math.max(1, s.getInt(key)));
                }
            }
        }
        return Map.copyOf(cost);
    }

    /** Base levels from base-levels; without the section, a single level with protected-max-hp. */
    private static List<BaseLevel> baseLevels(FileConfiguration c, int fallbackHp) {
        String[] roman = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        List<BaseLevel> levels = new java.util.ArrayList<>();
        for (Map<?, ?> m : c.getMapList("base-levels")) {
            int n = levels.size() + 1;
            Object hp = m.get("hp");
            Object cost = m.get("upgrade-cost");
            Object upkeep = m.get("upkeep-per-day");
            Object name = m.get("name");
            levels.add(new BaseLevel(n, name == null ? (n <= roman.length ? roman[n - 1] : String.valueOf(n)) : name.toString(),
                    Math.max(1, hp instanceof Number h ? h.intValue() : fallbackHp),
                    n == 1 ? 0 : Math.max(0, cost instanceof Number x ? x.doubleValue() : 0),
                    Math.max(0, upkeep instanceof Number u ? u.doubleValue() : 0)));
        }
        if (levels.isEmpty()) {
            levels.add(new BaseLevel(1, "I", fallbackHp, 0, 0));
        }
        return List.copyOf(levels);
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static Material material(String name, Material fallback) {
        Material m = name == null ? null : Material.matchMaterial(name);
        return m == null ? fallback : m;
    }

    private static List<Area> areas(List<Map<?, ?>> list, Logger log) {
        List<Area> result = new ArrayList<>();
        for (Map<?, ?> raw : list) {
            Area a = area(raw, log);
            if (a != null) {
                result.add(a);
            }
        }
        return List.copyOf(result);
    }

    static Area area(Map<?, ?> raw, Logger log) {
        try {
            List<?> from = (List<?>) raw.get("from");
            List<?> to = (List<?>) raw.get("to");
            return Area.of(String.valueOf(raw.get("name")), String.valueOf(raw.get("world")),
                    num(from, 0), num(from, 1), num(from, 2), num(to, 0), num(to, 1), num(to, 2));
        } catch (RuntimeException e) {
            log.warning("Skipped invalid zone: " + raw);
            return null;
        }
    }

    private static PrimeTime primeTime(FileConfiguration c, Logger log) {
        ZoneId zone;
        try {
            zone = ZoneId.of(c.getString("prime-time.zone", "Europe/Moscow"));
        } catch (DateTimeException e) {
            log.warning("Unknown time zone in prime-time.zone, using UTC");
            zone = ZoneId.of("UTC");
        }
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        List<String> dayNames = c.getStringList("prime-time.days");
        if (dayNames.isEmpty()) {
            days = EnumSet.allOf(DayOfWeek.class);
        } else {
            for (String d : dayNames) {
                try {
                    days.add(DayOfWeek.valueOf(d.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    log.warning("Unknown day in prime-time.days: " + d);
                }
            }
        }
        List<PrimeTime.Window> windows = new ArrayList<>();
        for (String spec : c.getStringList("prime-time.windows")) {
            try {
                windows.add(PrimeTime.Window.parse(spec));
            } catch (RuntimeException e) {
                log.warning("Invalid prime-time window: " + spec);
            }
        }
        return new PrimeTime(zone, Set.copyOf(days), List.copyOf(windows));
    }

    private static int num(List<?> list, int i) {
        return ((Number) list.get(i)).intValue();
    }

    /** Damage per hit by tool material: material name prefix (IRON_PICKAXE → iron). */
    public int damageFor(Material tool) {
        if (tool == null || tool.isAir()) {
            return toolDamage.getOrDefault("hand", 1);
        }
        String name = tool.name().toLowerCase(Locale.ROOT);
        boolean isTool = name.endsWith("_pickaxe") || name.endsWith("_axe") || name.endsWith("_shovel")
                || name.endsWith("_hoe") || name.endsWith("_sword");
        if (!isTool) {
            return toolDamage.getOrDefault("hand", 1);
        }
        String tier = name.substring(0, name.indexOf('_'));
        return toolDamage.getOrDefault(tier, toolDamage.getOrDefault("hand", 1));
    }

    /** Base HP of a wilderness block. */
    public int wildHp(Material block) {
        java.util.OptionalDouble listed = wildHpTable.find(block);
        if (listed.isPresent()) {
            return Math.max(1, (int) Math.round(listed.getAsDouble()));
        }
        var type = block.asBlockType();
        if (type == null) {
            return 1;
        }
        float hardness = type.getHardness();
        if (hardness < 0) {
            return 1; // unbreakable (bedrock): vanilla prevents breaking it anyway
        }
        if (hardness < wildFormula.softHardness()) {
            return 1;
        }
        int hp = (int) Math.round(hardness * wildFormula.multiplier());
        return Math.max(wildFormula.minHp(), Math.min(wildFormula.maxHp(), hp));
    }

    public boolean inNeutralZone(String world, int x, int y, int z) {
        return neutralZones.stream().anyMatch(zone -> zone.contains(world, x, y, z));
    }
}
