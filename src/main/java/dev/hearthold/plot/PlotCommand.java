package dev.hearthold.plot;

import dev.hearthold.i18n.Lang;
import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanException;
import dev.hearthold.clan.ClanService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/** /plot heart | info | name | size | invite | kick | list | rate | top */
public final class PlotCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("heart", "info", "name", "size", "invite", "kick", "list", "rate", "top");

    private final PlotService plots;
    private final PlotListener listener;
    private final ClanService clans;

    public PlotCommand(PlotService plots, PlotListener listener, ClanService clans) {
        this.plots = plots;
        this.listener = listener;
        this.clans = clans;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("top")) {
            top(sender);
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.tr("plot.players-only"));
            return true;
        }
        try {
            switch (sub) {
                case "heart" -> {
                    requireLeader(player);
                    player.getInventory().addItem(listener.createItem()).values()
                            .forEach(rest -> player.getWorld().dropItem(player.getLocation(), rest));
                    ok(player, Lang.tr("plot.heart.received"));
                }
                case "name" -> {
                    if (args.length < 2) {
                        throw new ClanException(Lang.tr("plot.usage.name"));
                    }
                    Plot p = ownPlotHere(player);
                    plots.rename(p, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                    ok(player, Lang.tr("plot.renamed", p.name()));
                }
                case "size" -> size(player, args);
                case "invite" -> member(player, args, true);
                case "kick" -> member(player, args, false);
                case "list" -> list(player);
                case "rate" -> rate(player);
                default -> info(player);
            }
        } catch (ClanException e) {
            player.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
        }
        return true;
    }

    private void size(Player player, String[] args) throws ClanException {
        if (args.length < 4) {
            throw new ClanException("/plot size <x> <y> <z>");
        }
        Plot p = ownPlotHere(player);
        try {
            plots.resize(p, Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
        } catch (NumberFormatException e) {
            throw new ClanException(Lang.tr("plot.size.integers"));
        }
        ok(player, Lang.tr("plot.size.set", p.name(), p.size()));
    }

    private void member(Player player, String[] args, boolean add) throws ClanException {
        if (args.length < 2) {
            throw new ClanException(Lang.tr("plot.usage.member", add ? "invite" : "kick"));
        }
        Plot p = ownPlotHere(player);
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            throw new ClanException(Lang.tr("plot.player-not-found"));
        }
        if (add) {
            plots.invite(p, target.getUniqueId());
            ok(player, Lang.tr("plot.member.added", args[1], p.name()));
            if (target.getPlayer() != null) {
                target.getPlayer().sendMessage(Component.text(Lang.tr("plot.member.invited", p.name()),
                        NamedTextColor.GREEN));
            }
        } else if (plots.kick(p, target.getUniqueId())) {
            ok(player, Lang.tr("plot.member.removed", args[1], p.name()));
        } else {
            throw new ClanException(Lang.tr("plot.member.not-in-plot"));
        }
    }

    private void info(Player player) throws ClanException {
        Plot p = plots.at(player.getLocation().getBlock())
                .orElseThrow(() -> new ClanException(Lang.tr("plot.info.not-in-plot")));
        String clan = clans.byId(p.clanId()).map(Clan::name).orElse("?");
        String members = p.members().stream().map(id -> Optional.ofNullable(Bukkit.getOfflinePlayer(id).getName())
                .orElse(id.toString().substring(0, 8))).collect(Collectors.joining(", "));
        info(player, Lang.tr("plot.info.title", p.name(), clan, p.size()));
        info(player, Lang.tr("plot.info.members", members.isEmpty() ? Lang.tr("plot.info.nobody") : members));
        info(player, p.ratedAt() > 0
                ? Lang.tr("plot.info.score", String.format(Locale.ROOT, "%.1f", p.score()), plots.tier(p))
                : Lang.tr("plot.info.unrated"));
    }

    private void list(Player player) throws ClanException {
        Clan own = clans.ofPlayer(player.getUniqueId()).orElseThrow(() -> new ClanException(Lang.tr("plot.not-in-clan")));
        List<Plot> list = plots.of(own.id());
        if (list.isEmpty()) {
            info(player, Lang.tr("plot.list.empty"));
        }
        for (Plot p : list) {
            info(player, Lang.tr("plot.list.entry", p.name(), p.size(),
                    p.members().size(), plots.tier(p)));
        }
        info(player, Lang.tr("plot.list.prestige", plots.prestige(own.id())));
    }

    private void rate(Player player) throws ClanException {
        Plot p = plots.at(player.getLocation().getBlock())
                .orElseThrow(() -> new ClanException(Lang.tr("plot.stand-inside")));
        Clan own = clans.ofPlayer(player.getUniqueId()).orElseThrow(() -> new ClanException(Lang.tr("plot.not-in-clan")));
        if (!own.id().equals(p.clanId())) {
            throw new ClanException(Lang.tr("plot.rate.own-only"));
        }
        double score = plots.rate(p);
        ok(player, Lang.tr("plot.rate.done", p.name(), String.format(Locale.ROOT, "%.1f", score),
                plots.tier(p), plots.prestige(own.id())));
    }

    private void top(CommandSender sender) {
        sender.sendMessage(Component.text(Lang.tr("plot.top.title"), NamedTextColor.GOLD));
        List<Plot> ranking = plots.ranking();
        for (int i = 0; i < Math.min(10, ranking.size()); i++) {
            Plot p = ranking.get(i);
            sender.sendMessage(Component.text("  " + Lang.tr("plot.top.entry", i + 1,
                    p.name(), clans.byId(p.clanId()).map(Clan::name).orElse("?"),
                    String.format(Locale.ROOT, "%.1f", p.score()), plots.tier(p)),
                    NamedTextColor.GRAY));
        }
        if (ranking.isEmpty()) {
            sender.sendMessage(Component.text("  " + Lang.tr("plot.top.empty"), NamedTextColor.GRAY));
        }
    }

    /** The plot of the leader's own clan that the leader is standing in. */
    private Plot ownPlotHere(Player player) throws ClanException {
        Clan own = requireLeader(player);
        Plot p = plots.at(player.getLocation().getBlock())
                .orElseThrow(() -> new ClanException(Lang.tr("plot.stand-inside")));
        if (!p.clanId().equals(own.id())) {
            throw new ClanException(Lang.tr("plot.other-clan"));
        }
        return p;
    }

    private Clan requireLeader(Player player) throws ClanException {
        Clan clan = clans.ofPlayer(player.getUniqueId()).orElseThrow(() -> new ClanException(Lang.tr("plot.not-in-clan")));
        if (!clan.isLeader(player.getUniqueId())) {
            throw new ClanException(Lang.tr("plot.leader-only"));
        }
        return clan;
    }

    private static void ok(Player player, String text) {
        player.sendMessage(Component.text(text, NamedTextColor.GREEN));
    }

    private static void info(Player player, String text) {
        player.sendMessage(Component.text(text, NamedTextColor.GRAY));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return SUBS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && List.of("invite", "kick").contains(args[0].toLowerCase(Locale.ROOT))) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
