package dev.hearthold.plot;

import dev.hearthold.i18n.Lang;
import dev.hearthold.base.TerritoryService;
import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanException;
import dev.hearthold.clan.ClanService;
import dev.hearthold.config.HeartholdConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Permissions on clan territory.
 * <ul>
 *   <li>The leader and players with the Builder tag can build and break anywhere on their base (plots included);
 *       other members only in plots they are invited to.</li>
 *   <li>Containers follow the chest access setting ({@link ChestAccess}); leaders open everything.
 *       Outsiders cannot open an inaccessible chest even during war; they can only break it in a siege.</li>
 *   <li>Outsiders build and break under the normal siege rules (HP).</li>
 * </ul>
 */
public final class PlotListener implements Listener {

    public enum Access { NO_PLOT, ALLOWED, MEMBER_DENIED, OUTSIDER }

    private final NamespacedKey key;
    private final NamespacedKey sizeKey;
    private final PlotService plots;
    private final TerritoryService territory;
    private final ClanService clans;
    private final ChestAccess chests;
    private final Supplier<HeartholdConfig> config;
    /** Blocks with their own access rules (collector barrels) are not checked here. */
    private Predicate<Block> exempt = b -> false;
    /** Right-click on a plot heart opens the plot UI. */
    private BiConsumer<Player, Plot> heartClick = (p, plot) -> { };
    /** Shift+right-click on a chest by the leader opens the access settings. */
    private BiConsumer<Player, Block> chestSettings = (p, b) -> { };

    public PlotListener(Plugin plugin, PlotService plots, TerritoryService territory, ClanService clans,
                        ChestAccess chests, Supplier<HeartholdConfig> config) {
        this.key = new NamespacedKey(plugin, "plot_heart");
        this.sizeKey = new NamespacedKey(plugin, "new_plot_size");
        this.plots = plots;
        this.territory = territory;
        this.clans = clans;
        this.chests = chests;
        this.config = config;
    }

    public void setExempt(Predicate<Block> exempt) {
        this.exempt = exempt;
    }

    public void onHeartClick(BiConsumer<Player, Plot> callback) {
        this.heartClick = callback;
    }

    public void onChestSettings(BiConsumer<Player, Block> callback) {
        this.chestSettings = callback;
    }

    public ChestAccess chests() {
        return chests;
    }

    public ItemStack createItem() {
        ItemStack item = new ItemStack(config.get().plot().material());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(Lang.tr("plot.item.name"), NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(Lang.tr("plot.item.lore1"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text(Lang.tr("plot.item.lore2"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text(Lang.tr("plot.item.lore3"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isItem(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    // --- New plot size: stored on the player ---

    public int[] newPlotSize(Player player) {
        int[] s = player.getPersistentDataContainer().get(sizeKey, PersistentDataType.INTEGER_ARRAY);
        int max = plots.maxSize();
        if (s == null || s.length != 3) {
            int d = Math.min(9, max);
            return new int[]{d, d, d};
        }
        return new int[]{clamp(s[0], max), clamp(s[1], max), clamp(s[2], max)};
    }

    public void setNewPlotSize(Player player, int[] size) {
        int max = plots.maxSize();
        player.getPersistentDataContainer().set(sizeKey, PersistentDataType.INTEGER_ARRAY,
                new int[]{clamp(size[0], max), clamp(size[1], max), clamp(size[2], max)});
    }

    private static int clamp(int v, int max) {
        return Math.max(1, Math.min(max, v));
    }

    // --- Permissions ---

    /** Whether a clan member may build and break across the whole base: leader or Builder. */
    public static boolean isBuilder(Clan clan, UUID player) {
        return clan.hasAccess(player, Clan.Tag.BUILDER);
    }

    /** Player rights in a plot (for instant building and free breaking). */
    public Access access(Player player, Block block) {
        Optional<Plot> plot = plots.at(block);
        if (plot.isEmpty()) {
            return Access.NO_PLOT;
        }
        Plot p = plot.get();
        if (p.members.contains(player.getUniqueId())) {
            return Access.ALLOWED;
        }
        Optional<Clan> own = clans.ofPlayer(player.getUniqueId());
        if (own.isPresent() && own.get().id().equals(p.clanId)) {
            return isBuilder(own.get(), player.getUniqueId()) ? Access.ALLOWED : Access.MEMBER_DENIED;
        }
        return Access.OUTSIDER;
    }

    /** A plot invitee (or clan builder) builds and breaks there freely (no HP, no slow placement). */
    public boolean isTrusted(Player player, Block block) {
        return access(player, block) == Access.ALLOWED;
    }

    /**
     * Why a member cannot build at this spot of their base (null if they can). Does not apply to outsiders
     * or to spots outside their own territory.
     */
    public String buildDenial(Player player, Block block) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            return null;
        }
        Optional<Clan> own = clans.ofPlayer(player.getUniqueId());
        if (own.isEmpty() || !own.get().id().equals(territory.claimAt(block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ()))) {
            return null;
        }
        if (isBuilder(own.get(), player.getUniqueId())) {
            return null;
        }
        Optional<Plot> plot = plots.at(block);
        if (plot.isPresent() && plot.get().members.contains(player.getUniqueId())) {
            return null;
        }
        return plot.isPresent() ? Lang.tr("plot.deny.foreign-plot")
                : Lang.tr("plot.deny.base");
    }

    private boolean deny(Player player, Block block) {
        String denial = buildDenial(player, block);
        if (denial != null) {
            player.sendActionBar(Component.text(denial, NamedTextColor.RED));
            return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (deny(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /** Plot heart broken: the plot is removed; chest broken: its access setting is removed too. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBroken(BlockBreakEvent event) {
        plots.heartAt(event.getBlock()).ifPresent(p -> {
            plots.remove(p);
            event.getPlayer().sendMessage(Component.text(Lang.tr("plot.removed", p.name()), NamedTextColor.YELLOW));
        });
        chests.forget(event.getBlock());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();
        if (isItem(event.getItemInHand())) {
            placeHeart(event, player, block);
            return;
        }
        if (deny(player, block)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (deny(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (deny(event.getPlayer(), event.getBlockClicked())) {
            event.setCancelled(true);
        }
    }

    private void placeHeart(BlockPlaceEvent event, Player player, Block block) {
        Optional<Clan> own = clans.ofPlayer(player.getUniqueId());
        if (own.isEmpty() || !own.get().isLeader(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(Component.text(Lang.tr("plot.heart.leader-only"), NamedTextColor.RED));
            return;
        }
        if (!territory.claimOf(block).map(c -> c.id().equals(own.get().id())).orElse(false)) {
            event.setCancelled(true);
            player.sendMessage(Component.text(Lang.tr("plot.heart.own-base"), NamedTextColor.RED));
            return;
        }
        if (plots.at(block).isPresent()) {
            event.setCancelled(true);
            player.sendMessage(Component.text(Lang.tr("plot.heart.occupied"), NamedTextColor.RED));
            return;
        }
        int[] size = newPlotSize(player);
        try {
            Plot p = plots.create(own.get().id(), block, size[0], size[1], size[2]);
            player.sendMessage(Component.text(Lang.tr("plot.created", p.name(), p.size()), NamedTextColor.GREEN));
        } catch (ClanException e) {
            event.setCancelled(true);
            player.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHeartInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null) {
            return;
        }
        Player player = event.getPlayer();
        if (player.isSneaking() && !player.getInventory().getItemInMainHand().isEmpty()) {
            return; // placing a block on the heart
        }
        plots.heartAt(event.getClickedBlock()).ifPresent(p -> {
            if (clans.ofPlayer(player.getUniqueId()).map(c -> c.id().equals(p.clanId())).orElse(false)) {
                event.setCancelled(true);
                heartClick.accept(player, p);
            }
        });
    }

    /** Whether the player may open the container per its setting (war not considered). */
    public boolean canOpen(Player player, Block block) {
        UUID claim = territory.claimAt(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        if (claim == null) {
            return true;
        }
        Optional<Clan> owner = clans.byId(claim);
        if (owner.isEmpty()) {
            return true;
        }
        Clan clan = owner.get();
        UUID id = player.getUniqueId();
        if (clan.isLeader(id)) {
            return true;
        }
        Optional<Plot> plot = plots.at(block).filter(p -> p.clanId.equals(claim));
        ChestAccess.Setting s = chests.of(block, plot.isPresent());
        return switch (s.mode()) {
            case EVERYONE -> true;
            case CLAN -> clan.isMember(id);
            case PLOT -> plot.map(p -> p.members.contains(id)).orElse(clan.isMember(id));
            case TAGS -> clan.isMember(id) && s.tags().stream().anyMatch(t -> clan.tagsOf(id).contains(t));
        };
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onContainer(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null || !(event.getClickedBlock().getState(false) instanceof Container)) {
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        boolean emptyHand = player.getInventory().getItemInMainHand().isEmpty();
        if (player.isSneaking() && !emptyHand || exempt.test(block)) {
            return;
        }
        UUID claim = territory.claimAt(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        if (claim == null) {
            return;
        }
        Optional<Clan> own = clans.ofPlayer(player.getUniqueId());
        boolean ownClan = own.isPresent() && own.get().id().equals(claim);
        // Shift+right-click with an empty hand by the leader: access settings.
        if (player.isSneaking() && ownClan && own.get().isLeader(player.getUniqueId())) {
            event.setCancelled(true);
            chestSettings.accept(player, block);
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE || canOpen(player, block)) {
            return;
        }
        event.setCancelled(true);
        if (ownClan) {
            boolean inPlot = plots.at(block).isPresent();
            player.sendActionBar(Component.text(Lang.tr("plot.container.no-access", chests.of(block, inPlot).describe()),
                    NamedTextColor.RED));
        } else {
            // Even during war a foreign chest cannot be opened, only broken (by HP in a siege); its contents drop.
            player.sendActionBar(Component.text(Lang.tr("plot.container.foreign"),
                    NamedTextColor.RED));
        }
    }
}
