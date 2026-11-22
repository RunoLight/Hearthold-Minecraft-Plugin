package dev.hearthold.config;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** A set of materials from the config: exact names and * patterns (e.g. "*_LOG"). */
public record MaterialSet(Set<Material> exact, List<Pattern> patterns) {

    public static MaterialSet parse(List<String> names, String path, Logger log) {
        Set<Material> exact = EnumSet.noneOf(Material.class);
        List<Pattern> patterns = new ArrayList<>();
        for (String raw : names) {
            String name = raw.toUpperCase(Locale.ROOT);
            if (name.contains("*")) {
                patterns.add(pattern(name));
            } else {
                Material m = Material.matchMaterial(name);
                if (m == null) {
                    log.warning("Unknown block in " + path + ": " + raw);
                } else {
                    exact.add(m);
                }
            }
        }
        return new MaterialSet(Set.copyOf(exact), List.copyOf(patterns));
    }

    /** "*_LOG" → a regular expression where * matches any substring. */
    public static Pattern pattern(String wildcard) {
        return Pattern.compile(Pattern.quote(wildcard).replace("*", "\\E.*\\Q"));
    }

    public boolean contains(Material m) {
        if (exact.contains(m)) {
            return true;
        }
        String name = m.name();
        return patterns.stream().anyMatch(p -> p.matcher(name).matches());
    }
}
