package dev.hearthold.config;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** Events: Nether waves, timed chests in the overworld, a trader with custom deals. */
public record EventConfig(
        double waveCooldownHours,
        int waveTimeoutSeconds,
        int waveAbandonSeconds,
        List<Map<EntityType, Integer>> waves,
        List<LootEntry> waveLoot,
        double chestIntervalHours,
        int chestAnnounceMinutes,
        int chestOpenMinutes,
        List<LootEntry> chestLoot,
        double traderChance,
        int traderMinutes,
        List<Trade> trades
) {

    /** A loot table row: item, min–max amount and drop chance. */
    public record LootEntry(Material item, int min, int max, double chance) { }

    /** A trader deal: what it takes (1–2 items), what it gives and how many times. */
    public record Trade(List<Stack> buy, Stack sell, int uses) { }

    public record Stack(Material item, int amount) { }

    static EventConfig load(FileConfiguration c, Logger log) {
        List<Map<EntityType, Integer>> waves = new ArrayList<>();
        for (Map<?, ?> raw : c.getMapList("events.wave.waves")) {
            Map<EntityType, Integer> wave = new LinkedHashMap<>();
            raw.forEach((k, v) -> {
                try {
                    wave.put(EntityType.valueOf(String.valueOf(k).toUpperCase(Locale.ROOT)),
                            Math.max(1, ((Number) v).intValue()));
                } catch (RuntimeException e) {
                    log.warning("Invalid mob in events.wave.waves: " + k);
                }
            });
            if (!wave.isEmpty()) {
                waves.add(Map.copyOf(wave));
            }
        }
        List<Trade> trades = new ArrayList<>();
        for (Map<?, ?> raw : c.getMapList("events.trader.trades")) {
            try {
                List<Stack> buy = ((List<?>) raw.get("buy")).stream().map(o -> stack(String.valueOf(o))).toList();
                Stack sell = stack(String.valueOf(raw.get("sell")));
                Object uses = raw.get("uses");
                trades.add(new Trade(buy.subList(0, Math.min(2, buy.size())), sell,
                        uses instanceof Number n ? Math.max(1, n.intValue()) : 4));
            } catch (RuntimeException e) {
                log.warning("Invalid trader deal: " + raw);
            }
        }
        return new EventConfig(
                Math.max(0, c.getDouble("events.wave.cooldown-hours", 6)),
                Math.max(30, c.getInt("events.wave.timeout-seconds", 180)),
                Math.max(10, c.getInt("events.wave.abandon-seconds", 60)),
                List.copyOf(waves),
                loot(c.getMapList("events.wave.loot"), log),
                Math.max(0.1, c.getDouble("events.chest.interval-hours", 8)),
                Math.max(0, c.getInt("events.chest.announce-minutes", 30)),
                Math.max(1, c.getInt("events.chest.open-minutes", 20)),
                loot(c.getMapList("events.chest.loot"), log),
                Math.max(0, Math.min(1, c.getDouble("events.trader.chance", 0.5))),
                Math.max(1, c.getInt("events.trader.minutes", 20)),
                List.copyOf(trades));
    }

    private static List<LootEntry> loot(List<Map<?, ?>> list, Logger log) {
        List<LootEntry> result = new ArrayList<>();
        for (Map<?, ?> raw : list) {
            Material m = Material.matchMaterial(String.valueOf(raw.get("item")));
            if (m == null) {
                log.warning("Unknown item in loot table: " + raw.get("item"));
                continue;
            }
            int min = raw.get("min") instanceof Number n ? n.intValue() : 1;
            int max = raw.get("max") instanceof Number n ? n.intValue() : min;
            double chance = raw.get("chance") instanceof Number n ? n.doubleValue() : 1.0;
            result.add(new LootEntry(m, Math.max(1, min), Math.max(min, max), Math.max(0, Math.min(1, chance))));
        }
        return List.copyOf(result);
    }

    /** "DIAMOND:2" → item stack. */
    private static Stack stack(String spec) {
        String[] kv = spec.split(":");
        Material m = Material.matchMaterial(kv[0]);
        if (m == null) {
            throw new IllegalArgumentException(spec);
        }
        return new Stack(m, kv.length > 1 ? Integer.parseInt(kv[1]) : 1);
    }
}
