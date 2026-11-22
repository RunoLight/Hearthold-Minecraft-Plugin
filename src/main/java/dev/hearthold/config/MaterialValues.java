package dev.hearthold.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * A "material → number" table from the config. Keys are exact names or * patterns ("*_LOG").
 * An exact name beats a pattern; among patterns the first one in config order wins.
 */
public record MaterialValues(Map<Material, Double> exact, List<Entry> patterns, double fallback) {

    public record Entry(Pattern pattern, double value) { }

    public static MaterialValues parse(ConfigurationSection section, double fallback, String path, Logger log) {
        Map<Material, Double> exact = new HashMap<>();
        List<Entry> patterns = new ArrayList<>();
        if (section != null) {
            for (String key : section.getKeys(false)) {
                double value = section.getDouble(key);
                String name = key.toUpperCase(Locale.ROOT);
                if (name.contains("*")) {
                    patterns.add(new Entry(MaterialSet.pattern(name), value));
                } else {
                    Material m = Material.matchMaterial(name);
                    if (m == null) {
                        log.warning("Unknown material in " + path + ": " + key);
                    } else {
                        exact.put(m, value);
                    }
                }
            }
        }
        return new MaterialValues(Map.copyOf(exact), List.copyOf(patterns), fallback);
    }

    /** The table value if the material is in it (exactly or by pattern). */
    public java.util.OptionalDouble find(Material m) {
        Double v = exact.get(m);
        if (v != null) {
            return java.util.OptionalDouble.of(v);
        }
        String name = m.name();
        for (Entry e : patterns) {
            if (e.pattern().matcher(name).matches()) {
                return java.util.OptionalDouble.of(e.value());
            }
        }
        return java.util.OptionalDouble.empty();
    }

    public double get(Material m) {
        Double v = exact.get(m);
        if (v != null) {
            return v;
        }
        String name = m.name();
        for (Entry e : patterns) {
            if (e.pattern().matcher(name).matches()) {
                return e.value();
            }
        }
        return fallback;
    }
}
