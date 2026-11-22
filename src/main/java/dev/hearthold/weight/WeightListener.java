package dev.hearthold.weight;

import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.config.WeightConfig;
import dev.hearthold.config.WeightConfig.Tier;
import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.ChestedHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Tiered overload. Every half second computes the load of players and mounts and applies
 * transient speed modifiers (not saved in the world: without the plugin the penalty disappears).
 * <ul>
 *   <li>Player on foot: own weight against own carrying capacity (+ potion).</li>
 *   <li>Player riding or in a minecart: own weight + mount cargo against the combined capacity;
 *       the mount gets the penalty.</li>
 *   <li>Donkey, mule, llama without a rider: chest cargo against the animal's capacity.</li>
 * </ul>
 */
public final class WeightListener implements Listener {

    private static final double MINECART_SPEED = 0.4;

    private final WeightService weights;
    private final Supplier<HeartholdConfig> config;
    private final NamespacedKey speedKey;
    private final NamespacedKey jumpKey;
    /** Current tier applied to the entity. */
    private final Map<UUID, Tier> applied = new HashMap<>();
    private int ticks;

    public WeightListener(Plugin plugin, WeightService weights, Supplier<HeartholdConfig> config) {
        this.weights = weights;
        this.config = config;
        this.speedKey = new NamespacedKey(plugin, "overload_speed");
        this.jumpKey = new NamespacedKey(plugin, "overload_jump");
    }

    /** Called every 10 ticks. */
    public void tick() {
        WeightConfig w = config.get().weight();
        Set<UUID> handled = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!w.enabled() || player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
                apply(player, Tier.NONE, w);
                continue;
            }
            double load = weights.weight(player);
            double capacity = weights.capacity(player);
            Entity vehicle = player.getVehicle();
            Double mountCap = vehicle == null ? null : w.mountCapacity().get(vehicle.getType());
            Tier tier;
            if (mountCap != null) {
                load += weights.cargo(vehicle);
                capacity += mountCap;
                tier = w.tier(load, capacity);
                apply(vehicle, tier, w);
                handled.add(vehicle.getUniqueId());
                apply(player, Tier.NONE, w);
            } else {
                tier = w.tier(load, capacity);
                apply(player, tier, w);
                if (tier.compareTo(Tier.HEAVY) >= 0 && player.isSprinting()) {
                    player.setSprinting(false);
                }
            }
            if (tier != Tier.NONE) {
                player.sendActionBar(Component.text(Lang.tr(mountCap != null ? "weight.overload.mount" : "weight.overload",
                        String.format(Locale.ROOT, "%.1f", load), String.format(Locale.ROOT, "%.1f", capacity), describe(tier)),
                        color(tier)));
            }
        }
        // Pack animals without a rider: every 2 seconds.
        if (++ticks % 4 == 0) {
            for (World world : Bukkit.getWorlds()) {
                for (ChestedHorse horse : world.getEntitiesByClass(ChestedHorse.class)) {
                    if (handled.contains(horse.getUniqueId())) {
                        continue;
                    }
                    Double cap = w.mountCapacity().get(horse.getType());
                    Tier tier = !w.enabled() || cap == null ? Tier.NONE : w.tier(weights.cargo(horse), cap);
                    apply(horse, tier, w);
                }
            }
        }
    }

    private void apply(Entity entity, Tier tier, WeightConfig w) {
        Tier before = applied.getOrDefault(entity.getUniqueId(), Tier.NONE);
        // Transient modifiers are lost when the entity unloads, so reapply them.
        boolean lost = tier != Tier.NONE && entity instanceof LivingEntity le
                && le.getAttribute(Attribute.MOVEMENT_SPEED) != null
                && le.getAttribute(Attribute.MOVEMENT_SPEED).getModifier(speedKey) == null;
        if (before == tier && !lost) {
            return;
        }
        if (tier == Tier.NONE) {
            applied.remove(entity.getUniqueId());
        } else {
            applied.put(entity.getUniqueId(), tier);
        }
        double factor = w.speedFactor(tier);
        if (entity instanceof Minecart cart) {
            cart.setMaxSpeed(MINECART_SPEED * factor);
            return;
        }
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        setModifier(living.getAttribute(Attribute.MOVEMENT_SPEED), speedKey, factor - 1.0);
        setModifier(living.getAttribute(Attribute.JUMP_STRENGTH), jumpKey, tier == Tier.STOP ? -1.0 : 0.0);
    }

    private static void setModifier(AttributeInstance attr, NamespacedKey key, double amount) {
        if (attr == null) {
            return;
        }
        attr.removeModifier(key);
        if (amount != 0.0) {
            attr.addTransientModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
    }

    public Tier tierOf(Player player) {
        return applied.getOrDefault(player.getUniqueId(), Tier.NONE);
    }

    private static String describe(Tier tier) {
        return switch (tier) {
            case NONE -> "";
            case SLOW -> Lang.tr("weight.tier.slow");
            case HEAVY -> Lang.tr("weight.tier.heavy");
            case STOP -> Lang.tr("weight.tier.stop");
        };
    }

    private static NamedTextColor color(Tier tier) {
        return switch (tier) {
            case SLOW -> NamedTextColor.YELLOW;
            case HEAVY -> NamedTextColor.GOLD;
            default -> NamedTextColor.RED;
        };
    }

    @EventHandler(ignoreCancelled = true)
    public void onSprint(PlayerToggleSprintEvent event) {
        if (event.isSprinting() && tierOf(event.getPlayer()).compareTo(Tier.HEAVY) >= 0) {
            event.setCancelled(true);
        }
    }

    /** No elytra flight while overloaded, otherwise weight would be pointless. */
    @EventHandler(ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (event.isGliding() && event.getEntity() instanceof Player p && tierOf(p) != Tier.NONE) {
            event.setCancelled(true);
            p.sendActionBar(Component.text(Lang.tr("weight.elytra-denied"), NamedTextColor.RED));
        }
    }

    /** With heavy overload an ender pearl does not teleport the player. */
    @EventHandler(ignoreCancelled = true)
    public void onPearl(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                && tierOf(event.getPlayer()).compareTo(Tier.HEAVY) >= 0) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text(Lang.tr("weight.pearl-denied"), NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExit(VehicleExitEvent event) {
        // A minecart without a passenger moves at normal speed; an animal is recalculated next tick.
        if (event.getVehicle() instanceof Minecart cart) {
            cart.setMaxSpeed(MINECART_SPEED);
            applied.remove(cart.getUniqueId());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrink(PlayerItemConsumeEvent event) {
        if (weights.isPotion(event.getItem())) {
            weights.drink(event.getPlayer());
            event.getPlayer().sendMessage(Component.text(Lang.tr("weight.potion.drunk",
                    String.format(Locale.ROOT, "%.0f", config.get().weight().potionBonus()),
                    config.get().weight().potionMinutes()), NamedTextColor.GREEN));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        applied.remove(event.getPlayer().getUniqueId());
    }
}
