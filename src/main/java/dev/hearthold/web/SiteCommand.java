package dev.hearthold.web;

import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanService;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.i18n.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/** /site — one-time portal login link; /site scouting [0-3] — clan scouting tier. */
public final class SiteCommand implements TabExecutor {

    private final WebServer server;
    private final ScoutingService scouting;
    private final ClanService clans;
    private final Supplier<HeartholdConfig> config;

    public SiteCommand(WebServer server, ScoutingService scouting, ClanService clans, Supplier<HeartholdConfig> config) {
        this.server = server;
        this.scouting = scouting;
        this.clans = clans;
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.tr("web.site.playersOnly"));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("scouting")) {
            scouting(player, args);
            return true;
        }
        if (server == null) {
            player.sendMessage(Component.text(Lang.tr("web.site.disabled"), NamedTextColor.RED));
            return true;
        }
        String link = server.issueLink(player.getUniqueId(), player.getName());
        player.sendMessage(Component.text(Lang.tr("web.site.link", config.get().web().tokenMinutes()) + " ",
                        NamedTextColor.GRAY)
                .append(Component.text(Lang.tr("web.site.open"), NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(link))));
        return true;
    }

    private void scouting(Player player, String[] args) {
        Optional<Clan> clan = clans.ofPlayer(player.getUniqueId());
        if (clan.isEmpty()) {
            player.sendMessage(Component.text(Lang.tr("web.site.noClan"), NamedTextColor.RED));
            return;
        }
        var web = config.get().web();
        if (args.length < 2) {
            int t = scouting.tier(clan.get().id());
            player.sendMessage(Component.text(Lang.tr("web.scouting.info", t, cost(web.costOf(t)),
                    web.slowDelaySeconds(), cost(web.costOf(1)), cost(web.costOf(2)), web.fastDelaySeconds(),
                    cost(web.costOf(3))), NamedTextColor.GRAY));
            return;
        }
        if (!clan.get().isLeader(player.getUniqueId())) {
            player.sendMessage(Component.text(Lang.tr("web.scouting.leaderOnly"), NamedTextColor.RED));
            return;
        }
        int tier;
        try {
            tier = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            tier = -1;
        }
        if (tier < 0 || tier > ScoutingService.MAX_TIER) {
            player.sendMessage(Component.text(Lang.tr("web.scouting.range", ScoutingService.MAX_TIER), NamedTextColor.RED));
            return;
        }
        String error = scouting.change(clan.get().id(), tier);
        if (error != null) {
            player.sendMessage(Component.text(error, NamedTextColor.RED));
            return;
        }
        ClanService.broadcast(clan.get(), Component.text(Lang.tr("web.scouting.changed", tier, cost(web.costOf(tier))),
                NamedTextColor.GREEN));
    }

    private static String cost(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return "scouting".startsWith(args[0].toLowerCase(Locale.ROOT)) ? List.of("scouting") : List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("scouting")) {
            return List.of("0", "1", "2", "3");
        }
        return List.of();
    }
}
