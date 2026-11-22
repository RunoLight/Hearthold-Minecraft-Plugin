package dev.hearthold.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** Map settings: world border, producer regions, collectors, ore respawn in canyons. */
public record MapConfig(
        List<Border> borders,
        int pregenParallel,
        List<Region> regions,
        CollectorSettings collector,
        OreRespawn oreRespawn
) {

    /** Square world border: centre and side length in blocks. */
    public record Border(String world, double centerX, double centerZ, double size) { }

    /** Producer region: what one collector inside it yields per hour, and how much. */
    public record Region(String name, Area area, Map<Material, Double> perHour) { }

    /** Part of a collector schematic: a box of offsets from the core and the blocks allowed in it. */
    public record StructurePart(int x1, int y1, int z1, int x2, int y2, int z2, MaterialSet blocks) { }

    public record CollectorSettings(
            List<StructurePart> structure,
            int maxPerClan,
            /** Collector cost in emeralds from the inventory; a clan's first collector is free. */
            double cost,
            int maxItems,
            List<Double> efficiency,
            int intervalMinutes) {

        /** Output multiplier of each collector when the clan has {@code count} of them. */
        public double efficiencyFor(int count) {
            if (efficiency.isEmpty() || count <= 0) {
                return 1.0;
            }
            return efficiency.get(Math.min(count, efficiency.size()) - 1);
        }
    }

    public record OreRespawn(
            Set<String> zones,
            int intervalMinutes,
            int perRun,
            int attempts,
            Map<Material, Double> weights,
            MaterialSet hosts) { }

    public Region regionAt(String world, int x, int z) {
        for (Region r : regions) {
            if (r.area().containsXZ(world, x, z)) {
                return r;
            }
        }
        return null;
    }

    static MapConfig load(FileConfiguration c, Logger log) {
        List<Border> borders = new ArrayList<>();
        for (Map<?, ?> raw : c.getMapList("map.borders")) {
            try {
                List<?> center = (List<?>) raw.get("center");
                borders.add(new Border(String.valueOf(raw.get("world")),
                        ((Number) center.get(0)).doubleValue(), ((Number) center.get(1)).doubleValue(),
                        ((Number) raw.get("size")).doubleValue()));
            } catch (RuntimeException e) {
                log.warning("Skipped invalid world border: " + raw);
            }
        }

        List<Region> regions = new ArrayList<>();
        for (Map<?, ?> raw : c.getMapList("regions")) {
            Area area = HeartholdConfig.area(raw, log);
            if (area == null) {
                continue;
            }
            Map<Material, Double> perHour = new LinkedHashMap<>();
            if (raw.get("resources") instanceof Map<?, ?> res) {
                for (var e : res.entrySet()) {
                    Material m = Material.matchMaterial(String.valueOf(e.getKey()));
                    if (m == null || !(e.getValue() instanceof Number n)) {
                        log.warning("Invalid resource in region " + area.name() + ": " + e.getKey());
                    } else {
                        perHour.put(m, Math.max(0.0, n.doubleValue()));
                    }
                }
            }
            regions.add(new Region(area.name(), area, Map.copyOf(perHour)));
        }

        List<StructurePart> structure = new ArrayList<>();
        for (Map<?, ?> raw : c.getMapList("collector.structure")) {
            try {
                List<?> from = (List<?>) raw.get("from");
                List<?> to = (List<?>) raw.get("to");
                List<String> blocks = ((List<?>) raw.get("blocks")).stream().map(String::valueOf).toList();
                structure.add(new StructurePart(
                        Math.min(n(from, 0), n(to, 0)), Math.min(n(from, 1), n(to, 1)), Math.min(n(from, 2), n(to, 2)),
                        Math.max(n(from, 0), n(to, 0)), Math.max(n(from, 1), n(to, 1)), Math.max(n(from, 2), n(to, 2)),
                        MaterialSet.parse(blocks, "collector.structure", log)));
            } catch (RuntimeException e) {
                log.warning("Skipped invalid collector schematic part: " + raw);
            }
        }
        List<Double> efficiency = c.getDoubleList("collector.efficiency").stream()
                .map(d -> Math.max(0.0, d)).toList();

        Map<Material, Double> weights = new LinkedHashMap<>();
        ConfigurationSection ores = c.getConfigurationSection("ore-respawn.ores");
        if (ores != null) {
            for (String key : ores.getKeys(false)) {
                Material m = Material.matchMaterial(key.toUpperCase(Locale.ROOT));
                if (m == null || !m.isBlock()) {
                    log.warning("Unknown ore in ore-respawn.ores: " + key);
                } else {
                    weights.put(m, Math.max(0.0, ores.getDouble(key)));
                }
            }
        }

        return new MapConfig(
                List.copyOf(borders),
                Math.max(1, c.getInt("map.pregen-parallel", 8)),
                List.copyOf(regions),
                new CollectorSettings(
                        List.copyOf(structure),
                        Math.max(0, c.getInt("collector.max-per-clan", 3)),
                        Math.max(0, c.getDouble("collector.cost-emeralds", 32)),
                        Math.max(1, c.getInt("collector.max-items", 576)),
                        efficiency,
                        Math.max(1, c.getInt("collector.interval-minutes", 10))),
                new OreRespawn(
                        Set.copyOf(c.getStringList("ore-respawn.zones")),
                        Math.max(1, c.getInt("ore-respawn.interval-minutes", 30)),
                        Math.max(0, c.getInt("ore-respawn.per-run", 20)),
                        Math.max(1, c.getInt("ore-respawn.attempts", 400)),
                        Map.copyOf(weights),
                        MaterialSet.parse(c.getStringList("ore-respawn.hosts"), "ore-respawn.hosts", log)));
    }

    private static int n(List<?> list, int i) {
        return ((Number) list.get(i)).intValue();
    }
}
