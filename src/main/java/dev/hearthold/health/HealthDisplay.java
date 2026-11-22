package dev.hearthold.health;

import dev.hearthold.config.HeartholdConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** HP bar above a block: a temporary TextDisplay entity that disappears after a pause in hits. */
public final class HealthDisplay {

    private static final int BAR_LENGTH = 10;

    private record Shown(TextDisplay entity, long expiresAt) { }

    private final Supplier<HeartholdConfig> config;
    private final Map<BlockKey, Shown> shown = new HashMap<>();

    public HealthDisplay(Plugin plugin, Supplier<HeartholdConfig> config) {
        this.config = config;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::expire, 10L, 10L);
    }

    public void show(Block block, double hp, int max) {
        BlockKey key = BlockKey.of(block);
        long expiresAt = System.currentTimeMillis() + config.get().displaySeconds() * 1000L;
        Component text = render(hp, max);

        Shown current = shown.get(key);
        if (current != null && current.entity().isValid()) {
            current.entity().text(text);
            shown.put(key, new Shown(current.entity(), expiresAt));
            return;
        }
        Location at = block.getLocation().add(0.5, 1.25, 0.5);
        TextDisplay entity = block.getWorld().spawn(at, TextDisplay.class, td -> {
            td.setPersistent(false);
            td.setBillboard(Display.Billboard.CENTER);
            td.setSeeThrough(true);
            td.text(text);
        });
        shown.put(key, new Shown(entity, expiresAt));
    }

    public void hide(Block block) {
        Shown s = shown.remove(BlockKey.of(block));
        if (s != null) {
            s.entity().remove();
        }
    }

    public void clearAll() {
        shown.values().forEach(s -> s.entity().remove());
        shown.clear();
    }

    private void expire() {
        long now = System.currentTimeMillis();
        shown.values().removeIf(s -> {
            if (now >= s.expiresAt() || !s.entity().isValid()) {
                s.entity().remove();
                return true;
            }
            return false;
        });
    }

    public static Component render(double hp, int max) {
        double ratio = max <= 0 ? 0 : Math.max(0, Math.min(1, hp / max));
        int filled = (int) Math.round(ratio * BAR_LENGTH);
        TextColor color = ratio > 0.6 ? NamedTextColor.GREEN : ratio > 0.3 ? NamedTextColor.YELLOW : NamedTextColor.RED;
        return Component.text("█".repeat(filled), color)
                .append(Component.text("█".repeat(BAR_LENGTH - filled), NamedTextColor.DARK_GRAY))
                .append(Component.text(" " + (int) Math.ceil(hp) + "/" + max, NamedTextColor.WHITE));
    }
}
