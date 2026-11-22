package dev.hearthold.economy;

import dev.hearthold.base.BaseItems;
import dev.hearthold.base.Sphere;
import dev.hearthold.base.TerritoryService;
import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanService;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Right-click on your clan's heart or node opens the clan menu (if wired), otherwise the legacy behaviour:
 * with emeralds tops up upkeep, Shift+right-click with an empty hand takes an energy cell, a plain right-click
 * shows the account status. Right-click with a cell on any other block pours energy into the chunk.
 */
public final class HeartInteractListener implements Listener {

    private final TerritoryService territory;
    private final ClanService clans;
    private final EconomyService economy;
    private final ChunkEnergyService chunks;
    private final EnergyCell cells;
    private final BaseItems baseItems;
    private final Supplier<HeartholdConfig> config;

    private java.util.function.BiPredicate<Player, Sphere> sphereMenu = (p, s) -> false;

    public void setSphereMenu(java.util.function.BiPredicate<Player, Sphere> menu) {
        this.sphereMenu = menu;
    }

    public HeartInteractListener(TerritoryService territory, ClanService clans, EconomyService economy,
                                 ChunkEnergyService chunks, EnergyCell cells, BaseItems baseItems,
                                 Supplier<HeartholdConfig> config) {
        this.territory = territory;
        this.clans = clans;
        this.economy = economy;
        this.chunks = chunks;
        this.cells = cells;
        this.baseItems = baseItems;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null) {
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        Optional<Sphere> sphere = territory.sphereAt(block);
        if (sphere.isPresent()) {
            event.setCancelled(true);
            onSphere(player, sphere.get());
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        OptionalDouble cell = cells.amount(hand);
        if (cell.isPresent()) {
            event.setCancelled(true);
            pour(player, block, hand, cell.getAsDouble());
        }
    }

    private void onSphere(Player player, Sphere sphere) {
        Optional<Clan> owner = clans.byId(sphere.clanId());
        if (owner.isEmpty()) {
            return;
        }
        Clan clan = owner.get();
        long now = System.currentTimeMillis();
        Component activation = sphere.isActive(now) ? null
                : Component.text(Lang.tr("economy.sphere.activating", sphere.kind().title(), clan.name(),
                        TerritoryService.timeLeft(sphere, now)), NamedTextColor.GOLD);
        if (!clan.isMember(player.getUniqueId())) {
            // Abandoned or vassal base: the heart has its own menu (take over the base, removal timer).
            if (sphere.kind() == Sphere.Kind.HEART && sphereMenu.test(player, sphere)) {
                return;
            }
            if (activation != null) {
                player.sendMessage(activation);
            }
            player.sendActionBar(Component.text(Lang.tr("economy.sphere.foreign", sphere.kind().title(), clan.name())
                    + (activation == null ? "" : Lang.tr("economy.sphere.foreign-activation",
                    TerritoryService.timeLeft(sphere, now))), NamedTextColor.GRAY));
            return;
        }
        if (activation != null) {
            player.sendMessage(activation);
        }
        // Heart menu: summary, treasury, cells, members, no chat needed.
        if (sphereMenu.test(player, sphere)) {
            return;
        }
        if (sphere.kind() != Sphere.Kind.HEART) {
            player.sendActionBar(Component.text(Lang.tr("economy.sphere.own-node", sphere.kind().title()),
                    NamedTextColor.GRAY));
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() == Material.EMERALD || hand.getType() == Material.EMERALD_BLOCK) {
            int amount = hand.getAmount() * (hand.getType() == Material.EMERALD_BLOCK ? 9 : 1);
            player.getInventory().setItemInMainHand(null);
            economy.deposit(clan, amount);
            player.sendMessage(Component.text(Lang.tr("economy.heart.deposited", amount), NamedTextColor.GREEN));
            status(player, clan);
            return;
        }
        if (hand.isEmpty() && player.isSneaking()) {
            HeartholdConfig.EnergySettings e = config.get().energy();
            if (!economy.takeEnergy(clan.id(), e.cellSize())) {
                player.sendMessage(Component.text(Lang.tr("economy.heart.no-energy",
                        String.format(Locale.ROOT, "%.0f", e.cellSize()),
                        String.format(Locale.ROOT, "%.0f", economy.energy(clan.id()))), NamedTextColor.RED));
                return;
            }
            player.getInventory().setItemInMainHand(cells.create(e.cellMaterial(), e.cellSize()));
            player.sendMessage(Component.text(Lang.tr("economy.heart.cell-taken"),
                    NamedTextColor.AQUA));
            return;
        }
        status(player, clan);
    }

    private void status(Player player, Clan clan) {
        double emeralds = economy.emeralds(clan.id());
        double cost = economy.dailyCost(clan.id());
        String left = cost <= 0 ? "" : Lang.tr("economy.heart.status-days",
                String.format(Locale.ROOT, "%.1f", emeralds / cost));
        player.sendMessage(Component.text(Lang.tr("economy.heart.status",
                String.format(Locale.ROOT, "%.1f", emeralds), left, String.format(Locale.ROOT, "%.1f", cost),
                String.format(Locale.ROOT, "%.0f", economy.energy(clan.id())),
                String.format(Locale.ROOT, "%.0f", economy.energyPerHour(clan.id())),
                Lang.tr(economy.isProtected(clan.id()) ? "economy.heart.protected-yes" : "economy.heart.protected-no")),
                NamedTextColor.GRAY));
        player.sendMessage(Component.text(Lang.tr("economy.heart.hint"),
                NamedTextColor.DARK_GRAY));
    }

    private void pour(Player player, Block block, ItemStack hand, double amount) {
        ChunkEnergyService.ChunkKey key = ChunkEnergyService.ChunkKey.of(block);
        double free = chunks.freeCapacity(key);
        if (free < amount) {
            player.sendMessage(Component.text(Lang.tr("economy.chunk.almost-full",
                    String.format(Locale.ROOT, "%.0f", free), String.format(Locale.ROOT, "%.0f", amount)), NamedTextColor.RED));
            return;
        }
        chunks.infuse(key, amount);
        hand.setAmount(hand.getAmount() - 1);
        player.sendMessage(Component.text(Lang.tr("economy.chunk.poured",
                String.format(Locale.ROOT, "%.0f", chunks.energy(key))), NamedTextColor.AQUA));
    }

    /** Plugin items (heart, node, cell) cannot be used in crafting. */
    @EventHandler
    public void onCraft(PrepareItemCraftEvent event) {
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (item != null && (cells.amount(item).isPresent() || baseItems.kindOf(item).isPresent())) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }
}
