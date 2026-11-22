package dev.hearthold.ui;

import dev.hearthold.body.BodyListener;
import dev.hearthold.body.CompassMenu;
import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.List;

/**
 * Hearthold compass: given on join and after death if missing, and via /compass.
 * Right-click opens the menu: nearest body, clans, detailed weight, clan, website, events, admin menu.
 */
public final class CompassService implements Listener, CommandExecutor {

    private final Plugin plugin;
    private final NamespacedKey key;
    private final BodyListener bodies;
    private final CompassMenu clansMenu;
    private final WeightMenu weightMenu;
    private final AdminMenu adminMenu;
    private final ClanGuide guide;
    private final HeartMenu heartMenu;
    private final dev.hearthold.collector.CollectorGuide collectorGuide;

    public CompassService(Plugin plugin, BodyListener bodies, CompassMenu clansMenu, WeightMenu weightMenu,
                          AdminMenu adminMenu, ClanGuide guide, HeartMenu heartMenu,
                          dev.hearthold.collector.CollectorGuide collectorGuide) {
        this.guide = guide;
        this.heartMenu = heartMenu;
        this.collectorGuide = collectorGuide;
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "hearthold_compass");
        this.bodies = bodies;
        this.clansMenu = clansMenu;
        this.weightMenu = weightMenu;
        this.adminMenu = adminMenu;
    }

    private CollectorsMenu collectorsMenu;
    private ActionsMenu actions;

    public void setActions(ActionsMenu actions) {
        this.actions = actions;
    }
    private ScoutingMenu scoutingMenu;

    public void setScoutingMenu(ScoutingMenu scoutingMenu) {
        this.scoutingMenu = scoutingMenu;
    }

    public void setCollectorsMenu(CollectorsMenu collectorsMenu) {
        this.collectorsMenu = collectorsMenu;
    }

    public ItemStack create() {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(Lang.tr("compass.item.name"), NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text(Lang.tr("compass.item.lore"), NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean is(ItemStack item) {
        return item != null && !item.isEmpty() && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    private boolean has(Player p) {
        return Arrays.stream(p.getInventory().getContents()).anyMatch(this::is);
    }

    /** Gives the compass if the player doesn't have one. */
    public boolean giveIfMissing(Player p) {
        if (has(p)) {
            return false;
        }
        p.getInventory().addItem(create()).values().forEach(rest -> p.getWorld().dropItem(p.getLocation(), rest));
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // One tick later: after the offline body/death is handled and the inventory is final.
        plugin.getServer().getScheduler().runTask(plugin, () -> giveIfMissing(event.getPlayer()));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> giveIfMissing(event.getPlayer()));
    }

    /** The compass is not dropped on death: a new one is given anyway. */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::is);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!is(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        openMain(event.getPlayer());
    }

    public void openMain(Player player) {
        Menu m = new Menu(3, Component.text(Lang.tr("compass.menu.title")));
        // Row 2: clan and navigation.
        m.set(10, Menu.icon(Material.WRITTEN_BOOK, Lang.tr("compass.menu.guide"), Lang.tr("compass.menu.guide.lore")), p -> {
            p.closeInventory();
            guide.open(p);
        });
        m.set(11, Menu.icon(Material.LODESTONE, Lang.tr("compass.menu.base"), Lang.tr("compass.menu.base.lore")), p -> {
            p.closeInventory();
            guide.pointToBase(p);
        });
        m.set(12, Menu.icon(Material.SHIELD, Lang.tr("compass.menu.clan"), Lang.tr("compass.menu.clan.lore1"),
                Lang.tr("compass.menu.clan.lore2"), Lang.tr("compass.menu.clan.lore3"), Lang.tr("compass.menu.clan.lore4")), p -> {
            if (!heartMenu.openOwn(p)) {
                if (actions != null) {
                    actions.openNoClan(p, this::openMain);
                } else {
                    p.closeInventory();
                }
            }
        });
        m.set(13, Menu.icon(Material.WHITE_BANNER, Lang.tr("compass.menu.clans"),
                Lang.tr("compass.menu.clans.lore1"), Lang.tr("compass.menu.clans.lore2")), p -> clansMenu.open(p, this::openMain));
        m.set(14, Menu.icon(Material.SKELETON_SKULL, Lang.tr("compass.menu.body"),
                Lang.tr("compass.menu.body.lore1"), Lang.tr("compass.menu.body.lore2")), p -> {
            p.closeInventory();
            bodies.pointToNearestBody(p);
        });
        m.set(15, Menu.icon(Material.RED_BED, Lang.tr("compass.menu.respawn")), p -> {
            p.closeInventory();
            var target = p.getRespawnLocation() != null ? p.getRespawnLocation() : p.getWorld().getSpawnLocation();
            p.setCompassTarget(target);
            p.sendActionBar(Component.text(Lang.tr("compass.menu.respawn.done"), NamedTextColor.GOLD));
        });
        // Row 3: reference.
        m.set(19, Menu.icon(Material.ANVIL, Lang.tr("compass.menu.weight"), Lang.tr("compass.menu.weight.lore")),
                p -> weightMenu.open(p, this::openMain));
        if (scoutingMenu != null) {
            m.set(20, Menu.icon(Material.SPYGLASS, Lang.tr("compass.menu.scouting"), Lang.tr("compass.menu.scouting.lore1"),
                    Lang.tr("compass.menu.scouting.lore2")), p -> scoutingMenu.open(p, this::openMain));
        }
        if (actions != null) {
            m.set(21, Menu.icon(Material.NETHER_STAR, Lang.tr("compass.menu.events"), Lang.tr("compass.menu.events.lore")), p -> actions.eventsMenu(p, this::openMain));
        } else {
            m.set(21, Menu.icon(Material.NETHER_STAR, Lang.tr("compass.menu.events"), Lang.tr("compass.menu.events.lore")), run("event"));
        }
        m.set(22, Menu.icon(Material.FILLED_MAP, Lang.tr("compass.menu.site"), Lang.tr("compass.menu.site.lore")), run("site"));
        if (player.hasPermission(AdminMenu.PERMISSION)) {
            m.set(25, Menu.icon(Material.COMMAND_BLOCK, Lang.tr("compass.menu.admin"), Lang.tr("compass.menu.admin.lore")),
                    p -> adminMenu.open(p, this::openMain));
        }
        m.open(player);
    }

    private static java.util.function.Consumer<Player> run(String command) {
        return p -> {
            p.closeInventory();
            p.performCommand(command);
        };
    }

    /** /compass: gives the compass (if missing) and opens the menu. */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.tr("compass.players-only"));
            return true;
        }
        if (giveIfMissing(player)) {
            player.sendMessage(Component.text(Lang.tr("compass.given"), NamedTextColor.GREEN));
        } else {
            openMain(player);
        }
        return true;
    }
}
