package dev.hearthold.body;

import dev.hearthold.base.TerritoryService;
import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanService;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.economy.DeathListener;
import dev.hearthold.i18n.Lang;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Offline player bodies and the compass.
 * <ul>
 *   <li>A player who logs out stays in the world as a body (a mannequin with their skin and gear). Only a player can kill a body.
 *       A killed body drops items from the inventory snapshot (with the death loss); on login the player loses their inventory.</li>
 *   <li>The compass (menu) can point at the nearest unprotected body: one outside its owner's base.</li>
 * </ul>
 */
public final class BodyListener implements Listener {

    private final BodyService bodies;
    private final TerritoryService territory;
    private final ClanService clans;
    private final Supplier<HeartholdConfig> config;
    private final Predicate<ItemStack> keepWhole;

    public BodyListener(BodyService bodies, TerritoryService territory, ClanService clans,
                        Supplier<HeartholdConfig> config, Predicate<ItemStack> keepWhole) {
        this.bodies = bodies;
        this.territory = territory;
        this.clans = clans;
        this.config = config;
        this.keepWhole = keepWhole;
    }

    // --- Quit and join ---

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (Bukkit.isStopping() || player.isDead()
                || player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        Location at = bodyLocation(player.getLocation());
        PlayerInventory inv = player.getInventory();
        ItemStack[] snapshot = Arrays.stream(inv.getContents())
                .map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new);
        double health = player.getHealth();
        Mannequin body = at.getWorld().spawn(at, Mannequin.class, m -> {
            m.setProfile(ResolvableProfile.resolvableProfile(player.getPlayerProfile()));
            m.customName(Component.text(Lang.tr("compass.body.name", player.getName()), NamedTextColor.GRAY));
            m.setCustomNameVisible(true);
            m.setDescription(Component.text(Lang.tr("compass.body.sleeping"), NamedTextColor.DARK_GRAY));
            m.setImmovable(true);
            m.setPersistent(true);
            m.setRemoveWhenFarAway(false);
            m.setBodyYaw(at.getYaw());
            if (Mannequin.validPoses().contains(Pose.SLEEPING)) {
                m.setPose(Pose.SLEEPING, true);
            }
            var maxHealth = m.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealth != null) {
                maxHealth.setBaseValue(20);
            }
            m.setHealth(Math.max(1, Math.min(20, health)));
            EntityEquipment eq = m.getEquipment();
            eq.setHelmet(clone(inv.getHelmet()));
            eq.setChestplate(clone(inv.getChestplate()));
            eq.setLeggings(clone(inv.getLeggings()));
            eq.setBoots(clone(inv.getBoots()));
            eq.setItemInMainHand(clone(inv.getItemInMainHand()));
            eq.setItemInOffHand(clone(inv.getItemInOffHand()));
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                try {
                    eq.setDropChance(slot, 0f);
                } catch (IllegalArgumentException ignored) {
                    // slot without a drop chance
                }
            }
        });
        spawnHitbox(body);
        bodies.create(player.getUniqueId(), player.getName(), body, snapshot);
    }

    public static final String TEST_BODY_PREFIX = "Тест_";

    /** For tests: a body of a non-existent player with items. A killed test body is removed from the database right away. */
    public void spawnTestBody(Location at, ItemStack[] items) {
        UUID fake = UUID.randomUUID();
        String name = TEST_BODY_PREFIX + fake.toString().substring(0, 4);
        Location loc = bodyLocation(at);
        Mannequin body = loc.getWorld().spawn(loc, Mannequin.class, m -> {
            m.customName(Component.text(Lang.tr("compass.body.name", name), NamedTextColor.GRAY));
            m.setCustomNameVisible(true);
            m.setDescription(Component.text(Lang.tr("compass.body.sleeping"), NamedTextColor.DARK_GRAY));
            m.setImmovable(true);
            m.setPersistent(true);
            m.setRemoveWhenFarAway(false);
            m.setBodyYaw(loc.getYaw());
            if (Mannequin.validPoses().contains(Pose.SLEEPING)) {
                m.setPose(Pose.SLEEPING, true);
            }
        });
        spawnHitbox(body);
        bodies.create(fake, name, body, items);
    }

    // --- Sleeper hitbox ---

    /** Tag on the Interaction hitbox: UUID of the body it forwards hits to. */
    private static final NamespacedKey HITBOX = new NamespacedKey("hearthold", "body_hitbox");
    /** Length of a lying body along the head direction, and the size of one hitbox piece. */
    private static final double BODY_LENGTH = 1.9;
    private static final float HITBOX_WIDTH = 1.0f;
    private static final float HITBOX_HEIGHT = 0.6f;

    /** Body yaw is snapped to 90°, so the square hitboxes line up exactly along the body. */
    private static Location bodyLocation(Location from) {
        Location at = from.clone();
        at.setYaw(Math.round(at.getYaw() / 90f) * 90f);
        at.setPitch(0);
        return at;
    }

    /**
     * The vanilla sleeping-pose hitbox is 0.2×0.2 at the feet. Two invisible Interaction entities
     * of 1×0.6 are placed along the body; a hit on them counts as a hit on the body. Without a bed the client lays the body
     * with its head towards (−cos yaw, sin yaw) from the feet.
     */
    private void spawnHitbox(Mannequin body) {
        Location feet = body.getLocation();
        double yaw = Math.toRadians(feet.getYaw());
        double hx = -Math.cos(yaw);
        double hz = Math.sin(yaw);
        for (double t : new double[]{BODY_LENGTH * 0.25, BODY_LENGTH * 0.75}) {
            Location at = new Location(feet.getWorld(), feet.getX() + hx * t, feet.getY(), feet.getZ() + hz * t);
            feet.getWorld().spawn(at, Interaction.class, i -> {
                i.setInteractionWidth(HITBOX_WIDTH);
                i.setInteractionHeight(HITBOX_HEIGHT);
                i.setResponsive(true);
                i.setPersistent(true);
                i.getPersistentDataContainer().set(HITBOX, PersistentDataType.STRING, body.getUniqueId().toString());
            });
        }
    }

    private static Optional<UUID> hitboxOwner(Entity e) {
        if (!(e instanceof Interaction)) {
            return Optional.empty();
        }
        String id = e.getPersistentDataContainer().get(HITBOX, PersistentDataType.STRING);
        return id == null ? Optional.empty() : Optional.of(UUID.fromString(id));
    }

    /** Removes the body's hitboxes (near its location). */
    private static void removeHitbox(Location at, UUID bodyEntity) {
        if (at == null || at.getWorld() == null) {
            return;
        }
        at.getWorld().getNearbyEntitiesByType(Interaction.class, at, 3).stream()
                .filter(i -> hitboxOwner(i).filter(bodyEntity::equals).isPresent())
                .forEach(Entity::remove);
    }

    /** A hit on the hitbox becomes the player's hit on the body itself (with their weapon and cooldown). */
    @EventHandler(priority = EventPriority.LOW)
    public void onHitboxDamage(EntityDamageByEntityEvent event) {
        Optional<UUID> owner = hitboxOwner(event.getEntity());
        if (owner.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        Player player = attacker(event);
        Entity body = Bukkit.getEntity(owner.get());
        if (body == null || bodies.byEntity(owner.get()).isEmpty()) {
            event.getEntity().remove(); // the body is gone: orphaned hitbox
            return;
        }
        if (player != null && body instanceof Mannequin m && !m.isDead()) {
            player.attack(m);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Optional<BodyService.Body> body = bodies.of(player.getUniqueId());
        if (body.isEmpty()) {
            return;
        }
        BodyService.Body b = body.get();
        if (!b.killed()) {
            removeEntity(b);
        } else {
            player.getInventory().clear();
            player.setLevel(0);
            player.setExp(0);
            Location respawn = Optional.ofNullable(player.getRespawnLocation())
                    .orElse(player.getWorld().getSpawnLocation());
            player.teleport(respawn);
            var max = player.getAttribute(Attribute.MAX_HEALTH);
            player.setHealth(max == null ? 20 : max.getValue());
            player.setFoodLevel(20);
            player.sendMessage(Component.text(Lang.tr("compass.body.killed-while-away", b.killer()),
                    NamedTextColor.RED));
        }
        bodies.delete(b);
    }

    private void removeEntity(BodyService.Body b) {
        b.location().ifPresent(loc -> {
            loc.getChunk().load();
            Entity e = Bukkit.getEntity(b.entityId());
            if (e != null) {
                e.remove();
            }
            removeHitbox(loc, b.entityId());
        });
    }

    // --- Body damage and death ---

    /** Only a player can kill a body: not mobs, lava or falling. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (bodies.byEntity(event.getEntity().getUniqueId()).isEmpty()) {
            return;
        }
        if (attacker(event) == null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent event) {
        Optional<BodyService.Body> body = bodies.byEntity(event.getEntity().getUniqueId());
        if (body.isEmpty()) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        List<ItemStack> items = new ArrayList<>(Arrays.stream(body.get().items()).filter(Objects::nonNull).toList());
        DeathListener.applyLoss(items, config.get().deathDropFraction(), keepWhole);
        Location at = event.getEntity().getLocation();
        removeHitbox(at, event.getEntity().getUniqueId());
        items.forEach(i -> at.getWorld().dropItemNaturally(at, i));
        Player killer = event.getEntity().getKiller();
        if (body.get().playerName().startsWith(TEST_BODY_PREFIX)) {
            bodies.delete(body.get());
        } else {
            bodies.markKilled(body.get(), killer == null ? Lang.tr("compass.body.unknown-killer") : killer.getName());
        }
    }

    private static Player attacker(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent e) {
            if (e.getDamager() instanceof Player p) {
                return p;
            }
            if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) {
                return p;
            }
        }
        return null;
    }

    // --- Compass ---

    /** Points the player's compass at the nearest unprotected body of another clan. */
    public void pointToNearestBody(Player player) {
        UUID ownClan = clans.ofPlayer(player.getUniqueId()).map(Clan::id).orElse(null);
        Location here = player.getLocation();
        Optional<Location> nearest = bodies.all().stream()
                .filter(b -> !b.killed() && !b.player().equals(player.getUniqueId()))
                .filter(b -> {
                    UUID bodyClan = clans.ofPlayer(b.player()).map(Clan::id).orElse(null);
                    if (bodyClan != null && bodyClan.equals(ownClan)) {
                        return false; // skip own clan
                    }
                    return b.location().map(l -> !isProtected(l, bodyClan)).orElse(false);
                })
                .map(b -> b.location().orElseThrow())
                .filter(l -> l.getWorld().equals(here.getWorld()))
                .min(Comparator.comparingDouble(l -> l.distanceSquared(here)));
        if (nearest.isEmpty()) {
            player.sendActionBar(Component.text(Lang.tr("compass.body.none"), NamedTextColor.GRAY));
            return;
        }
        player.setCompassTarget(nearest.get());
        player.sendActionBar(Component.text(Lang.tr("compass.body.pointed",
                (int) nearest.get().distance(here)), NamedTextColor.GOLD));
    }

    /** A body is protected if it lies on its own clan's base. */
    private boolean isProtected(Location l, UUID bodyClan) {
        if (bodyClan == null) {
            return false;
        }
        return bodyClan.equals(territory.claimAt(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ()));
    }

    private static ItemStack clone(ItemStack item) {
        return item == null ? null : item.clone();
    }
}
