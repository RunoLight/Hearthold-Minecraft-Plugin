package dev.hearthold.health;

import dev.hearthold.base.Sphere;
import dev.hearthold.base.TerritoryService;
import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanService;
import dev.hearthold.config.Area;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Right-click on a block with an empty hand or a regular tool: block HP, whose territory it is, and particle
 * outlines of that base's spheres (visible only to the inspector, for a few seconds). The normal right-click
 * action is not cancelled.
 */
public final class BlockInspectListener implements Listener {

    /** Protected block HP for the owning clan (by base level); defaults to the config value. */
    private java.util.function.ToIntFunction<java.util.UUID> protectedHp;

    public void setProtectedHp(java.util.function.ToIntFunction<java.util.UUID> protectedHp) {
        this.protectedHp = protectedHp;
    }

    private int protectedHp(HeartholdConfig c, java.util.UUID clanId) {
        return protectedHp == null ? c.protectedMaxHp() : protectedHp.applyAsInt(clanId);
    }

    private static final long COOLDOWN_MS = 400;
    private static final int SHOW_TICKS = 120;
    private static final int REFRESH_TICKS = 10;
    private static final String[] TOOL_SUFFIXES = {"_PICKAXE", "_AXE", "_SHOVEL", "_HOE", "_SWORD"};

    private final Plugin plugin;
    private final BlockHealthService health;
    private final TerritoryService territory;
    private final ClanService clans;
    private final HealthDisplay display;
    private final PrimeTimeClock primeTime;
    private final Supplier<HeartholdConfig> config;
    private final Map<UUID, Long> lastUse = new HashMap<>();
    private final Map<UUID, BukkitTask> outlines = new HashMap<>();
    private final Map<UUID, org.bukkit.entity.TextDisplay> labels = new HashMap<>();

    public BlockInspectListener(Plugin plugin, BlockHealthService health, TerritoryService territory, ClanService clans,
                                HealthDisplay display, PrimeTimeClock primeTime, Supplier<HeartholdConfig> config) {
        this.plugin = plugin;
        this.health = health;
        this.territory = territory;
        this.clans = clans;
        this.display = display;
        this.primeTime = primeTime;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null) {
            return;
        }
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.SPECTATOR || !isInspector(player.getInventory().getItemInMainHand())) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastUse.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MS) {
            return;
        }
        lastUse.put(player.getUniqueId(), now);
        inspect(player, event.getClickedBlock());
    }

    /** Empty hand or a tool without a special plugin function. */
    private boolean isInspector(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return true;
        }
        String name = item.getType().name();
        boolean tool = item.getType() == Material.SHEARS;
        for (String suffix : TOOL_SUFFIXES) {
            tool |= name.endsWith(suffix);
        }
        if (!tool) {
            return false;
        }
        String ns = plugin.getName().toLowerCase(java.util.Locale.ROOT);
        return !item.hasItemMeta() || item.getItemMeta().getPersistentDataContainer().getKeys().stream()
                .noneMatch(k -> k.getNamespace().equals(ns));
    }

    public void inspect(Player player, Block block) {
        HeartholdConfig c = config.get();
        BlockKey key = BlockKey.of(block);
        List<Component> lines = new java.util.ArrayList<>();
        lines.add(Component.translatable(block.getType().translationKey(), NamedTextColor.WHITE));

        Optional<Sphere> sphere = territory.sphereAt(block);
        Optional<Clan> claim = territory.claimOf(block);
        Optional<Clan> mine = clans.ofPlayer(player.getUniqueId());
        UUID outlineClan = null;

        if (sphere.isPresent()) {
            Sphere s = sphere.get();
            String clanName = clans.byId(s.clanId()).map(Clan::name).orElse("?");
            lines.add(Component.text(Lang.tr("health.inspect.sphere-of", s.kind().title(), clanName), NamedTextColor.GOLD));
            lines.add(Component.text(Lang.tr("health.inspect.radius", s.radius()), NamedTextColor.GRAY));
            long now = System.currentTimeMillis();
            if (!s.isActive(now)) {
                lines.add(Component.text(Lang.tr("health.inspect.activates-in", TerritoryService.timeLeft(s, now)), NamedTextColor.GOLD));
            }
            outlineClan = s.clanId();
        } else if (claim.isPresent()) {
            Clan owner = claim.get();
            outlineClan = owner.id();
            boolean own = mine.map(m -> m.id().equals(owner.id())).orElse(false);
            if (own && territory.isActivating(block)) {
                lines.add(wildHp(block, key, c));
                lines.add(Component.text(Lang.tr("health.inspect.own-activating"), NamedTextColor.GOLD));
                lines.add(Component.text(Lang.tr("health.inspect.hp-after-activation"), NamedTextColor.GRAY));
            } else if (own) {
                // HP of own blocks is always visible, to see how badly the enemy damaged them.
                int max = protectedHp(c, owner.id());
                lines.add(HealthDisplay.render(health.hp(key, max), max));
                lines.add(Component.text(Lang.tr("health.inspect.own-base"), NamedTextColor.GREEN));
                lines.add(Component.text(Lang.tr("health.inspect.own-breaks-freely"), NamedTextColor.GRAY));
            } else if (territory.ownerOf(block).isPresent()) {
                int max = protectedHp(c, owner.id());
                lines.add(HealthDisplay.render(health.hp(key, max), max));
                lines.add(Component.text(Lang.tr("health.inspect.base-of", owner.name()), NamedTextColor.RED));
                if (!primeTime.isActive()) {
                    lines.add(Component.text(Lang.tr("health.inspect.off-prime"), NamedTextColor.GRAY));
                }
            } else {
                lines.add(wildHp(block, key, c));
                lines.add(Component.text(Lang.tr(territory.isActivating(block)
                        ? "health.inspect.base-activating" : "health.inspect.base-unprotected", owner.name()),
                        NamedTextColor.GOLD));
            }
        } else {
            lines.add(wildHp(block, key, c));
            String zone = zoneAt(block, c);
            lines.add(Component.text(zone == null ? Lang.tr("health.inspect.wilderness") : Lang.tr("health.inspect.neutral-zone", zone),
                    NamedTextColor.GRAY));
        }
        showLabel(player, block, Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), lines));
        if (outlineClan != null) {
            showOutline(player, outlineClan, mine.map(m -> m.id()).orElse(null));
        }
    }

    private Component wildHp(Block block, BlockKey key, HeartholdConfig c) {
        int max = c.wildHp(block.getType());
        if (max <= 1 && !health.isTracked(key)) {
            return Component.text(Lang.tr("health.inspect.breaks-instantly"), NamedTextColor.GRAY);
        }
        return HealthDisplay.render(health.hp(key, max), max);
    }

    /** Label above the block visible only to the inspector; the player's previous label is removed. */
    private void showLabel(Player player, Block block, Component text) {
        org.bukkit.entity.TextDisplay old = labels.remove(player.getUniqueId());
        if (old != null) {
            old.remove();
        }
        org.bukkit.Location at = block.getLocation().add(0.5, 1.6, 0.5);
        org.bukkit.entity.TextDisplay td = block.getWorld().spawn(at, org.bukkit.entity.TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setVisibleByDefault(false);
            d.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            d.setSeeThrough(true);
            d.setAlignment(org.bukkit.entity.TextDisplay.TextAlignment.CENTER);
            d.text(text);
        });
        player.showEntity(plugin, td);
        labels.put(player.getUniqueId(), td);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            td.remove();
            labels.remove(player.getUniqueId(), td);
        }, 80L);
    }

    private static String zoneAt(Block b, HeartholdConfig c) {
        for (Area a : c.neutralZones()) {
            if (a.contains(b.getWorld().getName(), b.getX(), b.getY(), b.getZ())) {
                return a.name();
            }
        }
        return null;
    }

    /** Draws particle outlines of all clan spheres for a few seconds, only for this player. */
    private void showOutline(Player player, UUID clanId, UUID ownClan) {
        BukkitTask old = outlines.remove(player.getUniqueId());
        if (old != null) {
            old.cancel();
        }
        List<Sphere> spheres = territory.spheresOf(clanId).stream()
                .filter(s -> s.world().equals(player.getWorld().getName())).toList();
        if (spheres.isEmpty()) {
            return;
        }
        boolean own = clanId.equals(ownClan);
        int[] elapsed = {0};
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || elapsed[0] >= SHOW_TICKS) {
                BukkitTask t = outlines.remove(player.getUniqueId());
                if (t != null) {
                    t.cancel();
                }
                return;
            }
            // Shift the pattern every frame so the outline "shimmers" and reads better.
            double phase = elapsed[0] / (double) REFRESH_TICKS * 0.37;
            elapsed[0] += REFRESH_TICKS;
            for (Sphere s : spheres) {
                drawSphere(player, s, colorOf(s, own), phase);
            }
        }, 0L, REFRESH_TICKS);
        outlines.put(player.getUniqueId(), task);
    }

    private static Color colorOf(Sphere s, boolean own) {
        if (s.kind() == Sphere.Kind.PROXY) {
            return Color.fromRGB(0xF5A524);
        }
        return own ? Color.fromRGB(0x4CD964) : Color.fromRGB(0xFF4040);
    }

    /** Points on the sphere surface (Fibonacci spiral) plus a pillar in the centre. */
    private static void drawSphere(Player player, Sphere s, Color color, double phase) {
        double r = s.radius() + 0.5;
        int n = (int) Math.max(120, Math.min(900, 4 * Math.PI * r * r / 4));
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.6f);
        double golden = Math.PI * (3 - Math.sqrt(5));
        double cx = s.x() + 0.5, cy = s.y() + 0.5, cz = s.z() + 0.5;
        for (int i = 0; i < n; i++) {
            double y = 1 - (i + 0.5) / n * 2;
            double ring = Math.sqrt(1 - y * y);
            double theta = golden * i + phase;
            player.spawnParticle(Particle.DUST, cx + Math.cos(theta) * ring * r, cy + y * r,
                    cz + Math.sin(theta) * ring * r, 1, 0, 0, 0, 0, dust, true);
        }
        for (int dy = 0; dy < 6; dy++) {
            player.spawnParticle(Particle.END_ROD, cx, cy + 1 + dy * 0.6, cz, 1, 0, 0, 0, 0, null, true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastUse.remove(event.getPlayer().getUniqueId());
        org.bukkit.entity.TextDisplay label = labels.remove(event.getPlayer().getUniqueId());
        if (label != null) {
            label.remove();
        }
        BukkitTask t = outlines.remove(event.getPlayer().getUniqueId());
        if (t != null) {
            t.cancel();
        }
    }
}
