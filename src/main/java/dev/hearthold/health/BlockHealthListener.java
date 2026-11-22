package dev.hearthold.health;

import dev.hearthold.base.TerritoryService;
import dev.hearthold.clan.Clan;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Block breaking with HP: instead of breaking, a block loses HP and stays in place until its HP runs out.
 * <ul>
 *   <li>Own base: blocks break as usual.</li>
 *   <li>Foreign base: static protection HP; outside prime time a hit removes 1 HP.</li>
 *   <li>Wilderness: small base HP depending on block type.</li>
 *   <li>A new block always starts at 1 HP and regenerates to the maximum.</li>
 * </ul>
 */
public final class BlockHealthListener implements Listener {

    /** Protected block HP for the owning clan (by base level); defaults to the config value. */
    private java.util.function.ToIntFunction<java.util.UUID> protectedHp;

    public void setProtectedHp(java.util.function.ToIntFunction<java.util.UUID> protectedHp) {
        this.protectedHp = protectedHp;
    }

    private int protectedHp(HeartholdConfig c, java.util.UUID clanId) {
        return protectedHp == null ? c.protectedMaxHp() : protectedHp.applyAsInt(clanId);
    }

    private final BlockHealthService health;
    private final TerritoryService territory;
    private final HealthDisplay display;
    private final PrimeTimeClock primeTime;
    private final Supplier<HeartholdConfig> config;
    /** Siege rule: (player clan or null, owner clan) -> denial reason or null. */
    private java.util.function.BiFunction<java.util.UUID, java.util.UUID, String> siegeRule = (a, o) -> null;

    /** Player clan (id) or null. */
    private java.util.function.Function<java.util.UUID, java.util.UUID> clans = p -> null;

    public void setClanLookup(java.util.function.Function<java.util.UUID, java.util.UUID> lookup) {
        this.clans = lookup;
    }

    /** Who may break blocks freely besides the own clan (e.g. someone invited to a plot). */
    private java.util.function.BiPredicate<Player, Block> freeBreak = (p, b) -> false;

    public void setFreeBreak(java.util.function.BiPredicate<Player, Block> predicate) {
        this.freeBreak = predicate;
    }

    private DamagePopup popup;

    public void setDamagePopup(DamagePopup popup) {
        this.popup = popup;
    }

    public void setSiegeRule(java.util.function.BiFunction<java.util.UUID, java.util.UUID, String> rule) {
        this.siegeRule = rule;
    }

    public BlockHealthListener(BlockHealthService health, TerritoryService territory, HealthDisplay display,
                               PrimeTimeClock primeTime, Supplier<HeartholdConfig> config) {
        this.health = health;
        this.territory = territory;
        this.display = display;
        this.primeTime = primeTime;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        BlockKey key = BlockKey.of(block);
        HeartholdConfig c = config.get();

        if (player.getGameMode() == GameMode.CREATIVE) {
            breakFreely(block, key);
            return;
        }
        // A player invited to a plot breaks freely inside it.
        if (freeBreak.test(player, block)) {
            breakFreely(block, key);
            return;
        }
        // The own clan breaks its blocks as usual, even if upkeep is unpaid.
        if (territory.claimOf(block).filter(cl -> cl.isMember(player.getUniqueId())).isPresent()) {
            breakFreely(block, key);
            return;
        }
        Optional<Clan> owner = territory.ownerOf(block);

        int max;
        int damage;
        double wearMultiplier;
        double wearScale = 1;
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (owner.isPresent()) {
            String denial = siegeRule.apply(clans.apply(player.getUniqueId()), owner.get().id());
            if (denial != null) {
                event.setCancelled(true);
                player.sendActionBar(Component.text(denial, NamedTextColor.RED));
                return;
            }
            max = protectedHp(c, owner.get().id());
            damage = primeTime.isActive() ? c.damageFor(tool.getType()) : 1;
            wearMultiplier = c.durabilityMultiplier();
            if (!primeTime.isActive()) {
                wearScale = c.offPrimeWear();
            }
        } else {
            max = c.wildHp(block.getType());
            if (max <= 1 && !health.isTracked(key)) {
                return; // a regular wilderness block breaks as in vanilla
            }
            damage = c.damageFor(tool.getType());
            wearMultiplier = c.wildDurabilityMultiplier();
        }

        double before = health.hp(key, max);
        double remaining = health.hit(key, max, damage);
        double dealt = before - remaining;
        org.bukkit.Material toolType = tool.getType(); // before wear: the tool may break
        wearTool(player, tool, dealt, wearMultiplier, wearScale, remaining > 0);
        if (popup != null) {
            popup.show(block, toolType, dealt);
        }
        if (remaining <= 0) {
            // HP ran out: the block really breaks.
            breakFreely(block, key);
            return;
        }
        event.setCancelled(true);
        display.show(block, remaining, max);
        if (owner.isPresent() && !primeTime.isActive()) {
            player.sendActionBar(Component.text(Lang.tr("health.off-prime-hit",
                    String.format(java.util.Locale.ROOT, "%.0f", c.offPrimeWear())),
                    NamedTextColor.GRAY));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        BlockKey key = BlockKey.of(block);
        if (territory.sphereAt(block).isPresent()) {
            health.forget(key);
            return;
        }
        HeartholdConfig c = config.get();
        var owner = territory.ownerOf(block);
        int max = owner.isPresent() ? protectedHp(c, owner.get().id()) : c.wildHp(block.getType());
        if (max <= 1) {
            health.forget(key);
        } else {
            // A new block starts at 1 HP: a wall cannot be patched instantly during a siege.
            health.placed(key, max);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        protectTerritory(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        protectTerritory(event.blockList());
    }

    private void breakFreely(Block block, BlockKey key) {
        health.forget(key);
        display.hide(block);
    }

    /** Explosions do not remove HP yet: blocks on clan territory simply are not destroyed. */
    private void protectTerritory(List<Block> blocks) {
        blocks.removeIf(b -> territory.ownerOf(b).isPresent());
    }

    /**
     * Tool wear per hit: as much durability as HP removed from the block, plus the config multiplier on top.
     * Outside prime time on a foreign base, wear is multiplied by scale. If the block really broke, vanilla
     * removes 1 itself, so it is subtracted.
     */
    private static void wearTool(Player player, ItemStack tool, double dealt, double multiplier, double scale,
                                 boolean survived) {
        if (tool.isEmpty() || tool.getType().getMaxDurability() <= 0) {
            return;
        }
        int extra = (int) Math.ceil(((int) Math.ceil(dealt) + (int) Math.ceil(dealt * multiplier)) * scale) - (survived ? 0 : 1);
        if (extra > 0) {
            player.damageItemStack(EquipmentSlot.HAND, extra);
        }
    }
}
