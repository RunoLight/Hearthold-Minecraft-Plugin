package dev.hearthold.craft;

import com.destroystokyo.paper.event.block.AnvilDamagedEvent;
import dev.hearthold.base.TerritoryService;
import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanService;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BrewingStartEvent;
import org.bukkit.event.block.CampfireStartEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.inventory.FurnaceStartSmeltEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Timed crafting:
 * <ul>
 *   <li>Enchanting and anvil: the result is not handed out immediately but stays in the station for 2 min × level.</li>
 *   <li>Cooking, smelting and brewing take longer than vanilla (multipliers in the config).</li>
 *   <li>Tables, anvils and brewing stands can be placed only on the own base, with a per-clan limit,
 *       and only the own clan can use them.</li>
 * </ul>
 */
public final class CraftListener implements Listener {

    private final Plugin plugin;
    private final StationService stations;
    private final TerritoryService territory;
    private final ClanService clans;
    private final Supplier<HeartholdConfig> config;

    public CraftListener(Plugin plugin, StationService stations, TerritoryService territory, ClanService clans,
                         Supplier<HeartholdConfig> config) {
        this.plugin = plugin;
        this.stations = stations;
        this.territory = territory;
        this.clans = clans;
        this.config = config;
    }

    // --- Stations: placement, limit, breaking ---

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Optional<StationService.Type> type = StationService.Type.of(event.getBlockPlaced().getType());
        if (type.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        HeartholdConfig.CraftSettings c = config.get().craft();
        Optional<Clan> own = clans.ofPlayer(player.getUniqueId())
                .filter(cl -> territory.claimOf(event.getBlockPlaced()).map(Clan::id).filter(cl.id()::equals).isPresent());
        if (own.isEmpty()) {
            if (c.stationsOnlyInBase()) {
                event.setCancelled(true);
                player.sendMessage(Component.text(Lang.tr("misc.craft.only-own-base", type.get().title()),
                        NamedTextColor.RED));
            }
            return;
        }
        int limit = c.stationLimits().getOrDefault(type.get().key(), Integer.MAX_VALUE);
        if (stations.count(own.get().id(), type.get()) >= limit) {
            event.setCancelled(true);
            player.sendMessage(Component.text(Lang.tr("misc.craft.limit", type.get().title(), limit),
                    NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaced(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        StationService.Type.of(block.getType()).ifPresent(type -> clans.ofPlayer(event.getPlayer().getUniqueId())
                .filter(cl -> territory.claimOf(block).map(Clan::id).filter(cl.id()::equals).isPresent())
                .ifPresent(cl -> stations.register(block, cl.id(), type)));
    }

    /** A station was broken (by its owners or raiders): the uncollected item drops. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        stations.job(block).ifPresent(job -> {
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), job.item());
            stations.finish(job);
        });
        stations.unregister(block);
    }

    /** An anvil station does not fall or get damaged, otherwise the job would lose its place. */
    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityChangeBlockEvent event) {
        if (stations.station(event.getBlock()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onAnvilDamage(AnvilDamagedEvent event) {
        Location at = event.getInventory().getLocation();
        if (at != null && stations.station(at.getBlock()).isPresent()) {
            event.setCancelled(true);
        }
    }

    // --- Station use and collecting finished items ---

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null) {
            return;
        }
        Block block = event.getClickedBlock();
        Optional<StationService.Type> type = StationService.Type.of(block.getType());
        if (type.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        if (player.isSneaking() && !player.getInventory().getItemInMainHand().isEmpty()) {
            return; // placing a block next to it, not opening the station
        }
        Optional<UUID> playerClan = clans.ofPlayer(player.getUniqueId()).map(Clan::id);
        Optional<StationService.Job> job = stations.job(block);
        if (job.isPresent()) {
            event.setCancelled(true);
            StationService.Job j = job.get();
            boolean owner = j.clanId() == null || clans.byId(j.clanId()).isEmpty()
                    || playerClan.filter(j.clanId()::equals).isPresent();
            if (!j.isDone()) {
                player.sendActionBar(Component.text(Lang.tr("misc.craft.busy", StationService.name(j.item()),
                        StationService.remaining(j)), NamedTextColor.YELLOW));
            } else if (!owner) {
                player.sendActionBar(Component.text(Lang.tr("misc.craft.other-clan-item"), NamedTextColor.RED));
            } else {
                player.getInventory().addItem(j.item()).values()
                        .forEach(rest -> player.getWorld().dropItem(player.getLocation(), rest));
                stations.finish(j);
                player.sendMessage(Component.text(Lang.tr("misc.craft.collected", StationService.name(j.item())), NamedTextColor.GREEN));
            }
            return;
        }
        if (!config.get().craft().stationsOnlyInBase()) {
            return;
        }
        Optional<StationService.Station> station = stations.station(block);
        if (station.isEmpty()) {
            event.setCancelled(true);
            player.sendActionBar(Component.text(Lang.tr("misc.craft.unowned-station"),
                    NamedTextColor.RED));
        } else if (playerClan.filter(station.get().clanId()::equals).isEmpty()) {
            event.setCancelled(true);
            player.sendActionBar(Component.text(Lang.tr("misc.craft.other-clan-station"), NamedTextColor.RED));
        }
    }

    // --- Enchanting ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        double minutes = event.getExpLevelCost() * config.get().craft().enchantMinutesPerLevel();
        if (minutes <= 0) {
            return;
        }
        Player player = event.getEnchanter();
        Block table = event.getEnchantBlock();
        var top = event.getView().getTopInventory();
        // Vanilla enchants the item in the table slot; take it from there on the next tick.
        Bukkit.getScheduler().runTask(plugin, () -> {
            ItemStack result = top.getItem(0);
            if (result == null || result.isEmpty()) {
                return;
            }
            top.setItem(0, null);
            startJob(player, table, StationService.Type.ENCHANTING, result, minutes);
        });
    }

    // --- Anvil ---

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnvilClick(InventoryClickEvent event) {
        if (!(event.getView() instanceof AnvilView view) || event.getRawSlot() != 2
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack result = event.getCurrentItem();
        if (result == null || result.isEmpty()) {
            return;
        }
        double minutes = view.getRepairCost() * config.get().craft().anvilMinutesPerLevel();
        if (minutes <= 0) {
            return;
        }
        if (event.getAction() != InventoryAction.PICKUP_ALL && event.getAction() != InventoryAction.PICKUP_HALF) {
            event.setCancelled(true);
            player.sendActionBar(Component.text(Lang.tr("misc.craft.anvil-normal-click"), NamedTextColor.YELLOW));
            return;
        }
        Location at = view.getTopInventory().getLocation();
        if (at == null) {
            return;
        }
        ItemStack snapshot = result.clone();
        Bukkit.getScheduler().runTask(plugin, () -> {
            ItemStack cursor = player.getItemOnCursor();
            if (cursor.isEmpty() || !cursor.isSimilar(snapshot)) {
                return; // vanilla did not hand out the result (not enough levels, etc.)
            }
            player.setItemOnCursor(null);
            startJob(player, at.getBlock(), StationService.Type.ANVIL, cursor, minutes);
        });
    }

    private void startJob(Player player, Block block, StationService.Type type, ItemStack item, double minutes) {
        UUID clanId = clans.ofPlayer(player.getUniqueId()).map(Clan::id).orElse(null);
        StationService.Job job = stations.start(block, type, item, clanId, player.getName(), minutes);
        player.closeInventory();
        player.sendMessage(Component.text(Lang.tr("misc.craft.job-started",
                type.title(), StationService.name(item), StationService.remaining(job)), NamedTextColor.AQUA));
    }

    // --- Cooking and brewing ---

    @EventHandler(ignoreCancelled = true)
    public void onSmelt(FurnaceStartSmeltEvent event) {
        HeartholdConfig.CraftSettings c = config.get().craft();
        boolean food = event.getRecipe().getResult().getType().isEdible();
        event.setTotalCookTime(scale(event.getTotalCookTime(), food ? c.foodMultiplier() : c.smeltMultiplier()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onCampfire(CampfireStartEvent event) {
        event.setTotalCookTime(scale(event.getTotalCookTime(), config.get().craft().foodMultiplier()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBrew(BrewingStartEvent event) {
        int time = scale(event.getRecipeBrewTime(), config.get().craft().brewMultiplier());
        event.setRecipeBrewTime(time);
        event.setBrewingTime(time);
    }

    private static int scale(int ticks, double multiplier) {
        return (int) Math.max(1, Math.min(Short.MAX_VALUE, Math.round(ticks * multiplier)));
    }
}
