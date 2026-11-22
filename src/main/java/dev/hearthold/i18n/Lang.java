package dev.hearthold.i18n;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Server-wide translations.
 *
 * <p>Messages live in {@code src/main/resources/lang/<language>/*.properties} (UTF-8). Every file in the
 * language folder is loaded, so each feature area keeps its own file. Server owners can override any key
 * by dropping a file into {@code plugins/Hearthold/lang/<language>/}.
 *
 * <p>Lookup order: chosen language (data folder, then jar) → English → the key itself, so a missing
 * translation never breaks a message. Placeholders are positional: {@code {0}}, {@code {1}}, …; no
 * {@link java.text.MessageFormat} quoting rules apply, apostrophes are literal.
 */
public final class Lang {

    public static final String DEFAULT = "en";

    private static volatile Map<String, String> active = Map.of();
    private static volatile Map<String, String> fallback = Map.of();
    private static volatile String language = DEFAULT;

    private Lang() {
    }

    /** Loads (or reloads) translations for {@code lang}; unknown languages fall back to English. */
    public static void load(JavaPlugin plugin, String lang) {
        Logger log = plugin.getLogger();
        String code = lang == null || lang.isBlank() ? DEFAULT : lang.trim().toLowerCase(Locale.ROOT);
        Map<String, String> en = read(plugin, DEFAULT, log);
        Map<String, String> chosen = code.equals(DEFAULT) ? en : read(plugin, code, log);
        if (chosen.isEmpty() && !code.equals(DEFAULT)) {
            log.warning("No translations found for language '" + code + "', using English.");
            code = DEFAULT;
            chosen = en;
        }
        fallback = en;
        active = chosen;
        language = code;
        log.info("Language: " + code + " (" + chosen.size() + " messages)");
    }

    public static String language() {
        return language;
    }

    /** Translated message with {@code {0}}, {@code {1}}, … replaced by {@code args}. */
    public static String tr(String key, Object... args) {
        String s = active.get(key);
        if (s == null) s = fallback.get(key);
        if (s == null) return key;
        if (args.length == 0) return s;
        StringBuilder out = new StringBuilder(s.length() + 16);
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '{') {
                int close = s.indexOf('}', i + 1);
                if (close > i + 1) {
                    String idx = s.substring(i + 1, close);
                    if (idx.chars().allMatch(Character::isDigit) && idx.length() <= 2) {
                        int n = Integer.parseInt(idx);
                        if (n < args.length) {
                            out.append(args[n]);
                            i = close + 1;
                            continue;
                        }
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** True if the key exists in the active language or in English. */
    public static boolean has(String key) {
        return active.containsKey(key) || fallback.containsKey(key);
    }

    /**
     * Count-dependent message. Looks up {@code key.one}, {@code key.few}, {@code key.many} (Russian rules) or
     * {@code key.one} / {@code key.other} (English); the count is passed as {@code {0}} followed by {@code args}.
     */
    public static String plural(String key, long count, Object... args) {
        String form = form(count);
        String full = key + "." + form;
        if (!has(full)) full = key + (has(key + ".other") ? ".other" : ".many");
        Object[] all = new Object[args.length + 1];
        all[0] = count;
        System.arraycopy(args, 0, all, 1, args.length);
        return tr(full, all);
    }

    private static String form(long count) {
        long n = Math.abs(count);
        if (!language.equals("ru")) return n == 1 ? "one" : "other";
        long mod10 = n % 10, mod100 = n % 100;
        if (mod10 == 1 && mod100 != 11) return "one";
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "few";
        return "many";
    }

    private static Map<String, String> read(JavaPlugin plugin, String code, Logger log) {
        Map<String, String> out = new HashMap<>();
        String prefix = "lang/" + code + "/";
        try {
            Path jar = Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            try (JarFile jf = new JarFile(jar.toFile())) {
                Enumeration<JarEntry> entries = jf.entries();
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement();
                    if (e.isDirectory() || !e.getName().startsWith(prefix) || !e.getName().endsWith(".properties")) {
                        continue;
                    }
                    try (InputStream in = jf.getInputStream(e)) {
                        merge(out, in);
                    }
                }
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "Could not read bundled translations for '" + code + "'", e);
        }
        Path dir = plugin.getDataFolder().toPath().resolve("lang").resolve(code);
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.properties")) {
                for (Path f : files) {
                    try (InputStream in = Files.newInputStream(f)) {
                        merge(out, in);
                    }
                }
            } catch (IOException e) {
                log.log(Level.WARNING, "Could not read translation overrides in " + dir, e);
            }
        }
        return out;
    }

    private static void merge(Map<String, String> out, InputStream in) throws IOException {
        Properties p = new Properties();
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        for (String k : p.stringPropertyNames()) out.put(k, p.getProperty(k));
    }
}
