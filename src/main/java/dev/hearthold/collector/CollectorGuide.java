package dev.hearthold.collector;

import dev.hearthold.config.MapConfig;
import dev.hearthold.config.MaterialSet;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Clear instructions for the collector structure: layer-by-layer "blueprints" in chat (top view, north up),
 * a block list with counts, particle highlighting of missing blocks and a kit for admins.
 */
public final class CollectorGuide {

    /** Colours of structure parts in the blueprint and the highlight. */
    private static final int[] PART_RGB = {0x55FFFF, 0xFFAA00, 0xFF55FF, 0x55FF55, 0xFFFF55, 0x5555FF};

    /** A missing block: offset from the core and structure part index. */
    public record Missing(int dx, int dy, int dz, int part) { }

    private final Plugin plugin;
    private final Supplier<HeartholdConfig> config;
    private final Supplier<ItemStack> collectorItem;
    private final Map<UUID, BukkitTask> highlights = new HashMap<>();

    public CollectorGuide(Plugin plugin, Supplier<HeartholdConfig> config, Supplier<ItemStack> collectorItem) {
        this.plugin = plugin;
        this.config = config;
        this.collectorItem = collectorItem;
    }

    private List<MapConfig.StructurePart> parts() {
        return config.get().map().collector().structure();
    }

    private static TextColor color(int part) {
        return TextColor.color(PART_RGB[part % PART_RGB.length]);
    }

    /** Human-readable name of a block set: "smooth stone", "any logs". */
    public static Component label(MaterialSet set) {
        List<Component> names = new ArrayList<>();
        set.exact().stream().sorted().forEach(m -> names.add(Component.translatable(m.translationKey())));
        for (Pattern p : set.patterns()) {
            String w = p.pattern().replace("\\Q", "").replace("\\E", "").replace(".*", "*");
            names.add(Component.text(switch (w) {
                case "*_LOG" -> Lang.tr("collector.guide.any-log");
                case "*_PLANKS" -> Lang.tr("collector.guide.any-planks");
                case "*_WOOL" -> Lang.tr("collector.guide.any-wool");
                case "*_CONCRETE" -> Lang.tr("collector.guide.any-concrete");
                case "*_TERRACOTTA" -> Lang.tr("collector.guide.any-terracotta");
                default -> Lang.tr("collector.guide.any", w.toLowerCase(Locale.ROOT));
            }));
        }
        return Component.join(net.kyori.adventure.text.JoinConfiguration.separator(Component.text(Lang.tr("collector.guide.or"))), names);
    }

    private static int volume(MapConfig.StructurePart p) {
        return (p.x2() - p.x1() + 1) * (p.y2() - p.y1() + 1) * (p.z2() - p.z1() + 1);
    }

    private static boolean inside(MapConfig.StructurePart p, int x, int y, int z) {
        return x >= p.x1() && x <= p.x2() && y >= p.y1() && y <= p.y2() && z >= p.z1() && z <= p.z2();
    }

    /** Full instructions in chat. */
    public void sendInstructions(Player player) {
        List<MapConfig.StructurePart> parts = parts();
        player.sendMessage(Component.text(Lang.tr("collector.guide.title"), NamedTextColor.GOLD));
        player.sendMessage(Component.text(Lang.tr("collector.guide.step1"), NamedTextColor.GRAY));
        player.sendMessage(Component.text(Lang.tr("collector.guide.step2"), NamedTextColor.GRAY)
                .append(Component.text("■", NamedTextColor.GOLD)).append(Component.text(Lang.tr("collector.guide.step2.barrel"), NamedTextColor.GRAY)));
        int minX = 0, maxX = 0, minY = 0, maxY = 0, minZ = 0, maxZ = 0;
        for (MapConfig.StructurePart p : parts) {
            minX = Math.min(minX, p.x1());
            maxX = Math.max(maxX, p.x2());
            minY = Math.min(minY, p.y1());
            maxY = Math.max(maxY, p.y2());
            minZ = Math.min(minZ, p.z1());
            maxZ = Math.max(maxZ, p.z2());
        }
        for (int y = maxY; y >= minY; y--) {
            String title = y == 0 ? Lang.tr("collector.guide.layer.barrel") : y > 0 ? Lang.tr("collector.guide.layer.above", y)
                    : Lang.tr("collector.guide.layer.below", -y);
            player.sendMessage(Component.text(Lang.tr("collector.guide.layer", title), NamedTextColor.WHITE));
            for (int z = minZ; z <= maxZ; z++) {
                TextComponent.Builder row = Component.text().content("    ");
                for (int x = minX; x <= maxX; x++) {
                    TextColor c = NamedTextColor.DARK_GRAY;
                    if (x == 0 && y == 0 && z == 0) {
                        c = NamedTextColor.GOLD;
                    } else {
                        for (int i = 0; i < parts.size(); i++) {
                            if (inside(parts.get(i), x, y, z)) {
                                c = color(i);
                                break;
                            }
                        }
                    }
                    row.append(Component.text("■ ", c));
                }
                player.sendMessage(row.build());
            }
        }
        player.sendMessage(Component.text("  ■", NamedTextColor.DARK_GRAY)
                .append(Component.text(Lang.tr("collector.guide.anything"), NamedTextColor.GRAY)));
        for (Map.Entry<Component, Integer> e : legend().entrySet()) {
            player.sendMessage(e.getKey());
        }
        player.sendMessage(Component.text(Lang.tr("collector.guide.step3"),
                NamedTextColor.GRAY));
    }

    /** Legend lines: coloured square, blocks, count. */
    private Map<Component, Integer> legend() {
        List<MapConfig.StructurePart> parts = parts();
        Map<Component, Integer> lines = new LinkedHashMap<>();
        for (int i = 0; i < parts.size(); i++) {
            MapConfig.StructurePart p = parts.get(i);
            lines.put(Component.text("  ■ ", color(i)).append(label(p.blocks()).color(NamedTextColor.WHITE))
                    .append(Component.text(" × " + volume(p), NamedTextColor.GRAY)), volume(p));
        }
        return lines;
    }

    /** Which structure blocks around the core are not in place. */
    public List<Missing> missing(Block core) {
        List<Missing> result = new ArrayList<>();
        List<MapConfig.StructurePart> parts = parts();
        for (int i = 0; i < parts.size(); i++) {
            MapConfig.StructurePart p = parts.get(i);
            for (int dx = p.x1(); dx <= p.x2(); dx++) {
                for (int dy = p.y1(); dy <= p.y2(); dy++) {
                    for (int dz = p.z1(); dz <= p.z2(); dz++) {
                        if (!p.blocks().contains(core.getRelative(dx, dy, dz).getType())) {
                            result.add(new Missing(dx, dy, dz, i));
                        }
                    }
                }
            }
        }
        return result;
    }

    /** Missing blocks per part: "missing 3 × smooth stone". */
    public List<Component> describeMissing(List<Missing> missing) {
        Map<Integer, Integer> perPart = new LinkedHashMap<>();
        missing.forEach(m -> perPart.merge(m.part(), 1, Integer::sum));
        List<Component> lines = new ArrayList<>();
        List<MapConfig.StructurePart> parts = parts();
        perPart.forEach((part, n) -> lines.add(Component.text("  ■ ", color(part))
                .append(Component.text(Lang.tr("collector.guide.missing", n), NamedTextColor.RED))
                .append(label(parts.get(part).blocks()).color(NamedTextColor.WHITE))));
        return lines;
    }

    /** Highlights missing block positions with particles for ~10 seconds (visible only to the player). */
    public void highlight(Player player, Block core, List<Missing> missing) {
        BukkitTask old = highlights.remove(player.getUniqueId());
        if (old != null) {
            old.cancel();
        }
        if (missing.isEmpty()) {
            return;
        }
        int[] runs = {0};
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || runs[0]++ >= 20) {
                BukkitTask t = highlights.remove(player.getUniqueId());
                if (t != null) {
                    t.cancel();
                }
                return;
            }
            for (Missing m : missing) {
                Particle.DustOptions dust = new Particle.DustOptions(
                        Color.fromRGB(PART_RGB[m.part() % PART_RGB.length]), 1.2f);
                double x = core.getX() + m.dx(), y = core.getY() + m.dy(), z = core.getZ() + m.dz();
                // Cube edges: 3 points per edge, at the corners and midpoints.
                for (double a = 0; a <= 1.0; a += 0.5) {
                    for (double b = 0; b <= 1.0; b += 1.0) {
                        for (double c = 0; c <= 1.0; c += 1.0) {
                            player.spawnParticle(Particle.DUST, x + a, y + b, z + c, 1, 0, 0, 0, 0, dust, true);
                            player.spawnParticle(Particle.DUST, x + b, y + a, z + c, 1, 0, 0, 0, 0, dust, true);
                            player.spawnParticle(Particle.DUST, x + b, y + c, z + a, 1, 0, 0, 0, 0, dust, true);
                        }
                    }
                }
            }
        }, 0L, 10L);
        highlights.put(player.getUniqueId(), task);
    }

    /** Everything needed to build: the collector barrel and structure blocks (for patterns, the first match, preferably oak). */
    public List<ItemStack> kit() {
        List<ItemStack> items = new ArrayList<>();
        items.add(collectorItem.get());
        Map<Material, Integer> need = new LinkedHashMap<>();
        for (MapConfig.StructurePart p : parts()) {
            Material m = pick(p.blocks());
            if (m != null) {
                need.merge(m, volume(p), Integer::sum);
            }
        }
        need.forEach((m, n) -> {
            while (n > 0) {
                int stack = Math.min(n, m.getMaxStackSize());
                items.add(new ItemStack(m, stack));
                n -= stack;
            }
        });
        return items;
    }

    public void giveKit(Player player) {
        for (ItemStack item : kit()) {
            player.getInventory().addItem(item).values()
                    .forEach(rest -> player.getWorld().dropItem(player.getLocation(), rest));
        }
        player.sendMessage(Component.text(Lang.tr("collector.guide.kit-given"), NamedTextColor.GREEN));
        sendInstructions(player);
    }

    private static Material pick(MaterialSet set) {
        if (!set.exact().isEmpty()) {
            return set.exact().stream().filter(Material::isBlock).sorted().findFirst().orElse(null);
        }
        Material any = null;
        for (Material m : Material.values()) {
            if (m.name().startsWith("LEGACY_") || !m.isBlock() || !m.isItem() || !set.contains(m)) {
                continue;
            }
            if (m.name().startsWith("OAK_")) {
                return m;
            }
            if (any == null) {
                any = m;
            }
        }
        return any;
    }
}
