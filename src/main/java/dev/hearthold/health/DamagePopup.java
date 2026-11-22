package dev.hearthold.health;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** Floating "-N" label above a block on hit; colour depends on the tool material. */
public final class DamagePopup {

    private static final TextColor WOOD = TextColor.color(0xB0814F);
    private static final TextColor COPPER = TextColor.color(0xE07B4F);
    private static final TextColor HAND = TextColor.color(0xD0D0D0);

    private final Plugin plugin;

    public DamagePopup(Plugin plugin) {
        this.plugin = plugin;
    }

    public void show(Block block, Material tool, double damage) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location at = block.getLocation().add(0.5 + r.nextDouble(-0.3, 0.3), 1.05, 0.5 + r.nextDouble(-0.3, 0.3));
        String amount = damage == Math.rint(damage)
                ? String.valueOf((long) damage)
                : String.format(Locale.ROOT, "%.1f", damage);
        Component text = Component.text("-" + amount, colorOf(tool)).decoration(TextDecoration.BOLD, true);
        TextDisplay td = block.getWorld().spawn(at, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setSeeThrough(true);
            d.setShadowed(true);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
            d.text(text);
            d.setTeleportDuration(14);
        });
        // After a tick: smooth rise, then removal.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (td.isValid()) {
                td.teleport(td.getLocation().add(0, 0.7, 0));
            }
        }, 1L);
        plugin.getServer().getScheduler().runTaskLater(plugin, td::remove, 16L);
    }

    /** Tool "rarity" colour: wood, stone, copper, iron, gold, diamond, netherite. */
    public static TextColor colorOf(Material tool) {
        String n = tool == null ? "" : tool.name();
        if (n.startsWith("WOODEN_")) {
            return WOOD;
        }
        if (n.startsWith("STONE_")) {
            return NamedTextColor.GRAY;
        }
        if (n.startsWith("COPPER_")) {
            return COPPER;
        }
        if (n.startsWith("IRON_")) {
            return NamedTextColor.WHITE;
        }
        if (n.startsWith("GOLDEN_")) {
            return NamedTextColor.YELLOW;
        }
        if (n.startsWith("DIAMOND_")) {
            return NamedTextColor.AQUA;
        }
        if (n.startsWith("NETHERITE_")) {
            return NamedTextColor.LIGHT_PURPLE;
        }
        return HAND;
    }
}
