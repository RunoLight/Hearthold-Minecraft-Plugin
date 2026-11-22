package dev.hearthold.map;

import dev.hearthold.config.MapConfig;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.i18n.Lang;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * World border and map pre-generation.
 * <p>
 * Pre-generation walks every chunk inside the border row by row and asks the server to generate it
 * asynchronously, at most {@code pregen-parallel} at a time. Progress is saved to a file, so it can be
 * resumed from the same place after a restart.
 */
public final class MapService {

    /** How many already generated chunks may be skipped per tick without loading the server. */
    private static final int SKIPS_PER_TICK = 256;

    private final Plugin plugin;
    private final Supplier<HeartholdConfig> config;
    private final Path progressFile;

    private BukkitTask task;
    private World world;
    private int minCx, minCz, width, total;
    private int index;
    private final AtomicInteger inFlight = new AtomicInteger();
    private long lastReportAt;
    private Consumer<String> reporter = s -> { };

    public MapService(Plugin plugin, Supplier<HeartholdConfig> config) {
        this.plugin = plugin;
        this.config = config;
        this.progressFile = plugin.getDataFolder().toPath().resolve("pregen.properties");
    }

    /** Applies the configured borders to all loaded worlds. */
    public void applyBorders() {
        for (MapConfig.Border b : config.get().map().borders()) {
            World w = Bukkit.getWorld(b.world());
            if (w == null) {
                plugin.getLogger().warning("World for border not found: " + b.world());
                continue;
            }
            WorldBorder border = w.getWorldBorder();
            border.setCenter(b.centerX(), b.centerZ());
            border.setSize(b.size());
        }
    }

    public boolean isRunning() {
        return task != null;
    }

    /** Starts or resumes pre-generation of a world within its border. */
    public String start(String worldName, Consumer<String> reporter) {
        if (task != null) {
            return Lang.tr("admin.pregen.already-running", status());
        }
        World w = Bukkit.getWorld(worldName);
        if (w == null) {
            return Lang.tr("admin.pregen.world-not-found", worldName);
        }
        WorldBorder border = w.getWorldBorder();
        if (border.getSize() > 100_000) {
            return Lang.tr("admin.pregen.no-border", (long) border.getSize());
        }
        double half = border.getSize() / 2;
        int minX = (int) Math.floor(border.getCenter().getX() - half);
        int maxX = (int) Math.ceil(border.getCenter().getX() + half);
        int minZ = (int) Math.floor(border.getCenter().getZ() - half);
        int maxZ = (int) Math.ceil(border.getCenter().getZ() + half);
        this.world = w;
        this.minCx = minX >> 4;
        this.minCz = minZ >> 4;
        this.width = (maxX >> 4) - minCx + 1;
        this.total = width * ((maxZ >> 4) - minCz + 1);
        this.index = loadProgress(worldName).orElse(0);
        this.reporter = reporter;
        this.lastReportAt = System.currentTimeMillis();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::step, 1L, 1L);
        return Lang.tr("admin.pregen.started", worldName, total,
                index > 0 ? Lang.tr("admin.pregen.resume-suffix", index) : "");
    }

    public String stop() {
        if (task == null) {
            return Lang.tr("admin.pregen.not-running");
        }
        task.cancel();
        task = null;
        saveProgress();
        return Lang.tr("admin.pregen.stopped", status(), world.getName());
    }

    public String status() {
        if (world == null) {
            return Lang.tr("admin.pregen.never-started");
        }
        return String.format(Locale.ROOT, "%s %d / %d (%.1f%%)", world.getName(), index, total,
                total == 0 ? 100.0 : index * 100.0 / total);
    }

    private void step() {
        int parallel = config.get().map().pregenParallel();
        int skips = 0;
        while (index < total && inFlight.get() < parallel && skips < SKIPS_PER_TICK) {
            int cx = minCx + index % width;
            int cz = minCz + index / width;
            index++;
            if (world.isChunkGenerated(cx, cz)) {
                skips++;
                continue;
            }
            inFlight.incrementAndGet();
            world.getChunkAtAsync(cx, cz, true).whenComplete((chunk, error) -> inFlight.decrementAndGet());
        }
        long now = System.currentTimeMillis();
        if (now - lastReportAt > 30_000) {
            lastReportAt = now;
            saveProgress();
            reporter.accept(Lang.tr("admin.pregen.status", status(), ""));
        }
        if (index >= total && inFlight.get() == 0) {
            task.cancel();
            task = null;
            saveProgress();
            reporter.accept(Lang.tr("admin.pregen.finished", status()));
        }
    }

    private Optional<Integer> loadProgress(String worldName) {
        if (!Files.exists(progressFile)) {
            return Optional.empty();
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(progressFile)) {
            p.load(r);
            String v = p.getProperty(worldName);
            return v == null ? Optional.empty() : Optional.of(Math.min(total, Integer.parseInt(v)));
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    private void saveProgress() {
        if (world == null) {
            return;
        }
        Properties p = new Properties();
        try {
            Files.createDirectories(progressFile.getParent());
            if (Files.exists(progressFile)) {
                try (Reader r = Files.newBufferedReader(progressFile)) {
                    p.load(r);
                }
            }
            // In-flight chunks may not have finished generating; roll back to them on the next start.
            p.setProperty(world.getName(), String.valueOf(Math.max(0, index - inFlight.get())));
            try (Writer w = Files.newBufferedWriter(progressFile)) {
                p.store(w, "Hearthold: pre-generation progress (chunk index per world)");
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save pre-generation progress", e);
        }
    }
}
