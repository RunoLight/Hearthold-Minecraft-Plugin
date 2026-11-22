package dev.hearthold.war;

import dev.hearthold.i18n.Lang;
import dev.hearthold.base.BaseItems;
import dev.hearthold.base.TerritoryService;
import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanException;
import dev.hearthold.clan.ClanService;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.economy.EconomyService;
import dev.hearthold.trade.CaravanService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** /war: declaring war, peace, journal, vassals, tribute, patronage. */
public final class WarCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("declare", "peace", "end", "status", "journal", "vassals",
            "tribute", "patron");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d.MM HH:mm");

    private final ClanService clans;
    private final WarService wars;
    private final VassalService vassals;
    private final CaptureTask capture;
    private final CaravanService caravans;
    private final EconomyService economy;
    private final TerritoryService territory;
    private final BaseItems baseItems;
    private final Supplier<HeartholdConfig> config;

    public WarCommand(ClanService clans, WarService wars, VassalService vassals, CaptureTask capture,
                      CaravanService caravans, EconomyService economy, TerritoryService territory,
                      BaseItems baseItems, Supplier<HeartholdConfig> config) {
        this.clans = clans;
        this.wars = wars;
        this.vassals = vassals;
        this.capture = capture;
        this.caravans = caravans;
        this.economy = economy;
        this.territory = territory;
        this.baseItems = baseItems;
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "journal" -> journal(sender);
                case "vassals" -> vassalList(sender);
                default -> {
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(Lang.tr("war.players-only"));
                        return true;
                    }
                    switch (sub) {
                        case "declare" -> declare(player, args);
                        case "peace" -> peace(player, args);
                        case "end" -> withdraw(player, args);
                        case "tribute" -> tribute(player);
                        case "patron" -> patron(player);
                        default -> status(player);
                    }
                }
            }
        } catch (ClanException e) {
            sender.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
        }
        return true;
    }

    private void declare(Player player, String[] args) throws ClanException {
        if (args.length < 2) {
            throw new ClanException(Lang.tr("war.usage.declare"));
        }
        Clan own = requireLeader(player);
        Clan target = clans.byName(args[1]).orElseThrow(() -> new ClanException(Lang.tr("war.clan-not-found")));
        wars.declare(own, target);
        player.sendMessage(Component.text(Lang.tr("war.declared",
                String.format(Locale.ROOT, "%.1f", wars.fatigue(own.id())), String.format(Locale.ROOT, "%.2f", wars.upkeepMultiplier(own.id())), String.format(Locale.ROOT, "%.2f", wars.regenMultiplier(own.id()))),
                NamedTextColor.GOLD));
    }

    private void peace(Player player, String[] args) throws ClanException {
        if (args.length < 2) {
            throw new ClanException(Lang.tr("war.usage.peace"));
        }
        Clan own = requireLeader(player);
        Clan other = clans.byName(args[1]).orElseThrow(() -> new ClanException(Lang.tr("war.clan-not-found")));
        if (!wars.offerPeace(own, other)) {
            player.sendMessage(Component.text(Lang.tr("war.peace.offered", other.name(), own.name()), NamedTextColor.YELLOW));
            ClanService.broadcast(other, Component.text(Lang.tr("war.peace.incoming", own.name()), NamedTextColor.YELLOW));
        }
    }

    private void withdraw(Player player, String[] args) throws ClanException {
        if (args.length < 2) {
            throw new ClanException(Lang.tr("war.usage.end"));
        }
        Clan own = requireLeader(player);
        Clan other = clans.byName(args[1]).orElseThrow(() -> new ClanException(Lang.tr("war.clan-not-found")));
        wars.withdraw(own, other);
    }

    private void status(Player player) throws ClanException {
        Clan own = requireClan(player);
        long now = System.currentTimeMillis();
        info(player, Lang.tr("war.status.fatigue", String.format(Locale.ROOT, "%.1f", wars.fatigue(own.id())),
                String.format(Locale.ROOT, "%.2f", wars.upkeepMultiplier(own.id())), String.format(Locale.ROOT, "%.2f", wars.regenMultiplier(own.id()))));
        if (wars.isShielded(own.id())) {
            info(player, Lang.tr("war.status.shield", time(wars.shieldUntil(own.id()))));
        }
        vassals.suzerainOf(own.id()).ifPresent(v -> info(player, Lang.tr("war.status.vassal-of", v.suzerainName(),
                time(v.endsAt()))));
        for (VassalService.Vassalage v : vassals.vassalsOf(own.id())) {
            info(player, Lang.tr("war.status.vassal", v.vassalName(), time(v.endsAt()),
                    text(vassals.tribute().contents(v.id()))));
        }
        int captureSeconds = config.get().war().captureSeconds();
        for (War w : wars.ongoing()) {
            if (!w.involves(own.id())) {
                continue;
            }
            String state = w.isActive(now) ? Lang.tr("war.status.running-until", time(w.endsAt()))
                    : Lang.tr("war.status.preparing-until", time(w.activeAt()));
            info(player, Lang.tr("war.status.war", w.attackerName(), w.defenderName(), state,
                    String.format(Locale.ROOT, "%.0f", capture.progress(w.id())), captureSeconds));
        }
    }

    private void journal(CommandSender sender) {
        sender.sendMessage(Component.text(Lang.tr("war.journal.title"), NamedTextColor.GOLD));
        List<War> list = wars.journal(15);
        if (list.isEmpty()) {
            sender.sendMessage(Component.text("  " + Lang.tr("war.journal.empty"), NamedTextColor.GRAY));
        }
        for (War w : list) {
            String result = w.outcome() == null ? Lang.tr("war.journal.ongoing") : w.outcome().title();
            sender.sendMessage(Component.text("  " + time(w.declaredAt()) + " " + w.attackerName() + " → "
                    + w.defenderName() + ": " + result, NamedTextColor.GRAY));
        }
    }

    private void vassalList(CommandSender sender) {
        sender.sendMessage(Component.text(Lang.tr("war.vassals.title"), NamedTextColor.GOLD));
        List<VassalService.Vassalage> list = vassals.active();
        if (list.isEmpty()) {
            sender.sendMessage(Component.text("  " + Lang.tr("war.vassals.none"), NamedTextColor.GRAY));
        }
        for (VassalService.Vassalage v : list) {
            sender.sendMessage(Component.text("  " + Lang.tr("war.vassals.entry", v.vassalName(), v.suzerainName(),
                    v.kind() == VassalService.Kind.PATRONAGE ? " " + Lang.tr("war.vassals.patronage-suffix") : "",
                    time(v.endsAt())), NamedTextColor.GRAY));
        }
    }

    /** The suzerain's courier at the vassal base packs the tribute treasury into a suitcase. */
    private void tribute(Player player) throws ClanException {
        Clan own = requireClan(player);
        if (!own.canTrade(player.getUniqueId())) {
            throw new ClanException(Lang.tr("war.tribute.no-access"));
        }
        java.util.UUID here = territory.claimAt(player.getWorld().getName(), player.getLocation().getBlockX(),
                player.getLocation().getBlockY(), player.getLocation().getBlockZ());
        Optional<VassalService.Vassalage> v = vassals.vassalsOf(own.id()).stream()
                .filter(x -> x.vassal().equals(here)).findFirst();
        if (v.isEmpty()) {
            throw new ClanException(Lang.tr("war.tribute.stand-on-vassal"));
        }
        Map<Material, Integer> cargo = caravans.equipTribute(player, v.get().id(), Lang.tr("war.tribute.route-label"),
                v.get().vassal(), v.get().vassalName(), own.id());
        player.sendMessage(Component.text(Lang.tr("war.tribute.packed", CaravanService.describe(cargo),
                config.get().trade().standSeconds()),
                NamedTextColor.GREEN));
    }

    /** Patronage heart: paid from the patron's upkeep, given to a defeated clan. */
    private void patron(Player player) throws ClanException {
        Clan own = requireLeader(player);
        HeartholdConfig.WarSettings ws = config.get().war();
        long current = vassals.vassalsOf(own.id()).stream()
                .filter(v -> v.kind() == VassalService.Kind.PATRONAGE).count();
        if (current >= ws.patronageMax()) {
            throw new ClanException(Lang.tr("war.patron.max", ws.patronageMax()));
        }
        if (economy.emeralds(own.id()) < ws.patronageCost()) {
            throw new ClanException(Lang.tr("war.patron.cost",
                    String.format(Locale.ROOT, "%.0f", ws.patronageCost())));
        }
        economy.adjust(own, -ws.patronageCost());
        var item = baseItems.createPatronHeart(config.get().heart().material(), own.id(), own.name(), ws.patronageDays());
        player.getInventory().addItem(item).values()
                .forEach(rest -> player.getWorld().dropItem(player.getLocation(), rest));
        player.sendMessage(Component.text(Lang.tr("war.patron.ready"), NamedTextColor.GREEN));
    }

    private Clan requireClan(Player player) throws ClanException {
        return clans.ofPlayer(player.getUniqueId()).orElseThrow(() -> new ClanException(Lang.tr("war.not-in-clan")));
    }

    private Clan requireLeader(Player player) throws ClanException {
        Clan clan = requireClan(player);
        if (!clan.isLeader(player.getUniqueId())) {
            throw new ClanException(Lang.tr("war.leader-only"));
        }
        return clan;
    }

    private static String time(long at) {
        return TIME.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()));
    }

    private static String text(Map<Material, Integer> m) {
        return m.isEmpty() ? Lang.tr("war.empty") : CaravanService.describe(m);
    }

    private static void info(Player player, String text) {
        player.sendMessage(Component.text(text, NamedTextColor.GRAY));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return SUBS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && List.of("declare", "peace", "end").contains(args[0].toLowerCase(Locale.ROOT))) {
            return clans.all().stream().map(Clan::name)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
