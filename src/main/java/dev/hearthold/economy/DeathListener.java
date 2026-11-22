package dev.hearthold.economy;

import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Iterator;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * On death only part of the items drops: each unit drops with probability drop-fraction,
 * the rest is destroyed. This is an emerald and resource sink against inflation.
 */
public final class DeathListener implements Listener {

    private final Supplier<HeartholdConfig> config;
    /** Items that always drop whole on death (e.g. cargo suitcases). */
    private final java.util.function.Predicate<ItemStack> keepWhole;

    public DeathListener(Supplier<HeartholdConfig> config, java.util.function.Predicate<ItemStack> keepWhole) {
        this.config = config;
        this.keepWhole = keepWhole;
    }

    /**
     * Applies death loss to a list of items (in place): each unit survives with probability
     * {@code fraction}. Returns how many units were destroyed.
     */
    public static int applyLoss(java.util.List<ItemStack> items, double fraction,
                                java.util.function.Predicate<ItemStack> keepWhole) {
        if (fraction >= 1.0) {
            return 0;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int destroyed = 0;
        Iterator<ItemStack> it = items.iterator();
        while (it.hasNext()) {
            ItemStack item = it.next();
            if (item == null || keepWhole.test(item)) {
                continue;
            }
            int keep = 0;
            for (int i = 0; i < item.getAmount(); i++) {
                if (random.nextDouble() < fraction) {
                    keep++;
                }
            }
            destroyed += item.getAmount() - keep;
            if (keep == 0) {
                it.remove();
            } else {
                item.setAmount(keep);
            }
        }
        return destroyed;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (event.getKeepInventory()) {
            return;
        }
        double fraction = config.get().deathDropFraction();
        if (fraction >= 1.0) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int destroyed = 0;
        Iterator<ItemStack> it = event.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack item = it.next();
            if (keepWhole.test(item)) {
                continue;
            }
            int keep = 0;
            for (int i = 0; i < item.getAmount(); i++) {
                if (random.nextDouble() < fraction) {
                    keep++;
                }
            }
            destroyed += item.getAmount() - keep;
            if (keep == 0) {
                it.remove();
            } else {
                item.setAmount(keep);
            }
        }
        if (destroyed > 0) {
            event.getEntity().sendMessage(Component.text(Lang.tr("economy.death.destroyed", destroyed),
                    NamedTextColor.GRAY));
        }
    }
}
