package dev.hearthold.collector;

import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanException;
import dev.hearthold.clan.ClanService;
import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Placing, breaking and inspecting resource collectors. */
public final class CollectorListener implements Listener {

    private final Plugin plugin;
    private final NamespacedKey key;
    private final CollectorService collectors;
    private final ClanService clans;
    private final CollectorGuide guide;

    public CollectorListener(Plugin plugin, CollectorService collectors, ClanService clans,
                             java.util.function.Supplier<dev.hearthold.config.HeartholdConfig> config) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "collector");
        this.collectors = collectors;
        this.clans = clans;
        this.guide = new CollectorGuide(plugin, config, this::createItem);
    }

    public CollectorGuide guide() {
        return guide;
    }

    public ItemStack createItem() {
        ItemStack item = new ItemStack(Material.BARREL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(Lang.tr("collector.item.name"), NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(Lang.tr("collector.item.lore1"), NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text(Lang.tr("collector.item.lore2"), NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text(Lang.tr("collector.item.lore3"), NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isItem(ItemStack item) {
        return item != null && !item.isEmpty() && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!isItem(event.getItemInHand())) {
            return;
        }
        Player player = event.getPlayer();
        Optional<Clan> clan = clans.ofPlayer(player.getUniqueId());
        if (clan.isEmpty()) {
            event.setCancelled(true);
            player.sendMessage(Component.text(Lang.tr("collector.place.no-clan"), NamedTextColor.RED));
            return;
        }
        try {
            Collector col = collectors.place(clan.get(), event.getBlockPlaced());
            player.sendMessage(Component.text(Lang.tr("collector.place.done"), NamedTextColor.GREEN));
            guide.sendInstructions(player);
            // Status on the next tick, once the block is in the world.
            plugin.getServer().getScheduler().runTask(plugin, () -> describe(player, col));
        } catch (ClanException e) {
            event.setCancelled(true);
            player.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
        }
    }

    /** Core broken (by its own clan or after a siege): the collector is removed and the item drops intact. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        collectors.at(block).ifPresent(col -> {
            collectors.remove(col);
            event.setDropItems(false);
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), createItem());
        });
    }

    /** Right-click on the core: collector status in chat; the barrel opens as usual. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null) {
            return;
        }
        collectors.at(event.getClickedBlock()).ifPresent(col -> {
            collectors.flush(col);
            describe(event.getPlayer(), col);
        });
    }

    /** Only the owning clan's leader and quartermasters can open the collector barrel. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(org.bukkit.event.inventory.InventoryOpenEvent event) {
        if (!(event.getInventory().getHolder() instanceof org.bukkit.block.Barrel barrel)
                || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        collectors.at(barrel.getBlock()).ifPresent(col -> {
            if (!collectors.canLoot(col, player.getUniqueId())) {
                event.setCancelled(true);
                player.sendMessage(Component.text(Lang.tr("collector.open.denied"),
                        NamedTextColor.RED));
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBurn(BlockBurnEvent event) {
        if (collectors.at(event.getBlock()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> collectors.at(b).isPresent());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> collectors.at(b).isPresent());
    }

    private void describe(Player player, Collector col) {
        String clanName = clans.byId(col.clanId()).map(Clan::name).orElse("?");
        var rates = collectors.ratePerHour(col);
        var line = Component.text().append(Component.text(Lang.tr("collector.describe.header", clanName), NamedTextColor.GRAY));
        if (rates.isEmpty()) {
            line.append(Component.text(Lang.tr("collector.describe.nothing"), NamedTextColor.GRAY));
        }
        boolean first = true;
        for (var e : rates.entrySet()) {
            line.append(Component.text(first ? "" : ", ", NamedTextColor.GRAY))
                    .append(Component.translatable(e.getKey().translationKey(), NamedTextColor.AQUA))
                    .append(Component.text(Lang.tr("collector.describe.rate", String.format(Locale.ROOT, "%.1f", e.getValue())), NamedTextColor.GRAY));
            first = false;
        }
        player.sendMessage(line.build());
        List<CollectorGuide.Missing> missing = col.isLoaded() ? guide.missing(col.block()) : List.of();
        if (col.isLoaded()) {
            col.structureOk = missing.isEmpty() && col.block().getType() == Material.BARREL;
        }
        Optional<String> problem = collectors.problem(col);
        if (problem.isPresent()) {
            player.sendMessage(Component.text(Lang.tr("collector.describe.broken", problem.get()), NamedTextColor.RED));
        } else {
            player.sendMessage(Component.text(Lang.tr("collector.describe.working"), NamedTextColor.GREEN));
        }
        if (!missing.isEmpty()) {
            guide.describeMissing(missing).forEach(player::sendMessage);
            player.sendMessage(Component.text(Lang.tr("collector.describe.highlighted"),
                    NamedTextColor.GRAY));
            guide.highlight(player, col.block(), missing);
        }
    }
}
