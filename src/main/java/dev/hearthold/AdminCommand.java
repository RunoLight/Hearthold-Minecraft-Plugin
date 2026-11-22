package dev.hearthold;

import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.config.Area;
import dev.hearthold.economy.ChunkEnergyService;
import dev.hearthold.health.BlockKey;
import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Admin debug and maintenance commands. */
public final class AdminCommand implements TabExecutor {

    private static String usage() {
        return Lang.tr("admin.cmd.usage");
    }

    private final HeartholdPlugin plugin;

    public AdminCommand(HeartholdPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        switch (sub) {
            case "reload" -> {
                plugin.reloadHeartholdConfig();
                sender.sendMessage(Component.text(Lang.tr("admin.cmd.reloaded"), NamedTextColor.GREEN));
            }
            case "hp" -> hp(sender);
            case "prime" -> prime(sender, args);
            case "zones" -> zones(sender);
            case "energy" -> energy(sender);
            case "upkeep" -> upkeep(sender, args);
            case "buyer" -> buyer(sender, args);
            case "region" -> region(sender);
            case "ores" -> sender.sendMessage(Component.text(Lang.tr("admin.cmd.ores-spawned", plugin.oreRespawn().runNow()), NamedTextColor.GREEN));
            case "pregen" -> pregen(sender, args);
            case "potion" -> {
                if (sender instanceof Player p) {
                    p.getInventory().addItem(plugin.weights().createPotion());
                }
            }
            default -> {
                if (args.length == 0 && sender instanceof Player p) {
                    plugin.adminMenu().open(p, Player::closeInventory);
                } else {
                    sender.sendMessage(Component.text(usage(), NamedTextColor.YELLOW));
                }
            }
        }
        return true;
    }

    /** HP of the block the admin is looking at, and the territory owner. */
    private void hp(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.tr("admin.cmd.players-only"));
            return;
        }
        Block block = player.getTargetBlockExact(6);
        if (block == null) {
            sender.sendMessage(Component.text(Lang.tr("admin.cmd.look-at-block"), NamedTextColor.RED));
            return;
        }
        HeartholdConfig c = plugin.heartholdConfig();
        var owner = plugin.territory().ownerOf(block);
        int max = owner.isPresent() ? plugin.baseLevels().maxHp(owner.get().id()) : c.wildHp(block.getType());
        double hp = plugin.blockHealth().hp(BlockKey.of(block), max);
        sender.sendMessage(Component.text(Lang.tr("admin.cmd.hp", block.getType(), (int) Math.ceil(hp), max,
                owner.map(cl -> cl.name()).orElse(Lang.tr("admin.cmd.hp.wilderness")),
                c.inNeutralZone(block.getWorld().getName(), block.getX(), block.getY(), block.getZ())
                        ? Lang.tr("admin.cmd.hp.neutral") : "",
                plugin.blockHealth().trackedCount()), NamedTextColor.GRAY));
    }

    private void prime(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            switch (args[1].toLowerCase()) {
                case "on" -> plugin.primeTime().force(true);
                case "off" -> plugin.primeTime().force(false);
                case "auto" -> plugin.primeTime().force(null);
                default -> {
                    sender.sendMessage(Component.text("/hearthold prime [on|off|auto]", NamedTextColor.YELLOW));
                    return;
                }
            }
        }
        var pt = plugin.heartholdConfig().primeTime();
        Boolean forced = plugin.primeTime().forced();
        sender.sendMessage(Component.text(Lang.tr("admin.cmd.prime",
                Lang.tr(plugin.primeTime().isActive() ? "admin.cmd.prime.active" : "admin.cmd.prime.inactive"),
                Lang.tr(forced == null ? "admin.cmd.prime.scheduled" : "admin.cmd.prime.forced"),
                pt.windows().stream().map(Object::toString).collect(Collectors.joining(", ")),
                pt.zone()), NamedTextColor.GRAY));
    }

    private void zones(CommandSender sender) {
        List<Area> zones = plugin.heartholdConfig().neutralZones();
        if (zones.isEmpty()) {
            sender.sendMessage(Component.text(Lang.tr("admin.cmd.no-zones"), NamedTextColor.GRAY));
        }
        zones.forEach(z -> sender.sendMessage(Component.text(z.describe(), NamedTextColor.GRAY)));
    }

    private void energy(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.tr("admin.cmd.players-only"));
            return;
        }
        var key = ChunkEnergyService.ChunkKey.of(player.getLocation().getBlock());
        var chunks = plugin.chunkEnergy();
        sender.sendMessage(Component.text(Lang.tr("admin.cmd.energy",
                key.cx(), key.cz(), String.format(Locale.ROOT, "%.1f", chunks.energy(key)),
                String.format(Locale.ROOT, "%.1f", chunks.naturalMax(key)), chunks.trackedCount()),
                NamedTextColor.GRAY));
    }

    private void upkeep(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text(Lang.tr("admin.cmd.upkeep.usage"), NamedTextColor.YELLOW));
            return;
        }
        var clan = plugin.clans().byName(args[1]);
        if (clan.isEmpty()) {
            sender.sendMessage(Component.text(Lang.tr("admin.cmd.clan-not-found"), NamedTextColor.RED));
            return;
        }
        double amount;
        try {
            amount = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text(Lang.tr("admin.cmd.bad-number"), NamedTextColor.RED));
            return;
        }
        if (amount > 0) {
            plugin.economy().deposit(clan.get(), amount);
        } else {
            plugin.economy().adjust(clan.get(), amount);
        }
        sender.sendMessage(Component.text(Lang.tr("admin.cmd.upkeep.result",
                clan.get().name(), String.format(Locale.ROOT, "%.1f", plugin.economy().emeralds(clan.get().id()))),
                NamedTextColor.GREEN));
    }

    private void buyer(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.tr("admin.cmd.players-only"));
            return;
        }
        String action = args.length >= 2 ? args[1].toLowerCase() : "";
        switch (action) {
            case "spawn" -> {
                plugin.buyer().spawn(player.getLocation());
                sender.sendMessage(Component.text(Lang.tr("admin.cmd.buyer.spawned"), NamedTextColor.GREEN));
            }
            case "remove" -> {
                Entity target = player.getTargetEntity(6);
                if (target == null || !plugin.buyer().isBuyer(target)) {
                    sender.sendMessage(Component.text(Lang.tr("admin.cmd.buyer.look"), NamedTextColor.RED));
                    return;
                }
                target.remove();
                sender.sendMessage(Component.text(Lang.tr("admin.cmd.buyer.removed"), NamedTextColor.GREEN));
            }
            default -> sender.sendMessage(Component.text("/hearthold buyer spawn|remove", NamedTextColor.YELLOW));
        }
    }

    private void region(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.tr("admin.cmd.players-only"));
            return;
        }
        var loc = player.getLocation();
        var region = plugin.collectors().regionAt(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockZ());
        sender.sendMessage(Component.text(region == null ? Lang.tr("admin.cmd.region.none")
                : Lang.tr("admin.cmd.region", region.name(), region.perHour().entrySet().stream()
                        .map(e -> Lang.tr("admin.cmd.region.rate", e.getKey().name().toLowerCase(Locale.ROOT), e.getValue()))
                        .collect(Collectors.joining(", "))), NamedTextColor.GRAY));
    }

    private void pregen(CommandSender sender, String[] args) {
        String action = args.length >= 2 ? args[1].toLowerCase() : "status";
        String reply = switch (action) {
            case "start" -> {
                String world = args.length >= 3 ? args[2]
                        : sender instanceof Player p ? p.getWorld().getName() : "world";
                yield plugin.map().start(world, msg -> {
                    plugin.getLogger().info(msg);
                    if (sender instanceof Player p && p.isOnline()) {
                        p.sendMessage(Component.text(msg, NamedTextColor.GRAY));
                    }
                });
            }
            case "stop" -> plugin.map().stop();
            default -> Lang.tr("admin.pregen.status", plugin.map().status(),
                    plugin.map().isRunning() ? Lang.tr("admin.pregen.running-suffix") : "");
        };
        sender.sendMessage(Component.text(reply, NamedTextColor.YELLOW));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("reload", "hp", "prime", "zones", "energy", "upkeep", "buyer", "region", "ores", "pregen", "potion").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("prime")) {
            return List.of("on", "off", "auto").stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("buyer")) {
            return List.of("spawn", "remove").stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("pregen")) {
            return List.of("start", "stop", "status").stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("pregen") && args[1].equalsIgnoreCase("start")) {
            return org.bukkit.Bukkit.getWorlds().stream().map(w -> w.getName())
                    .filter(n -> n.startsWith(args[2])).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("upkeep")) {
            return plugin.clans().all().stream().map(c -> c.name())
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }
        return List.of();
    }
}
