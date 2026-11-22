package dev.hearthold.trade;

import dev.hearthold.base.TerritoryService;
import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanException;
import dev.hearthold.clan.ClanService;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.i18n.Lang;
import net.kyori.adventure.inventory.Book;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/** /trade: trade routes, treasury, caravans, stable, reputation. */
public final class TradeCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("propose", "offers", "accept", "decline", "list", "book",
            "pending", "equip", "forgive", "terminate", "failures", "rep", "log", "treasury", "stable");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d.MM");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d.MM HH:mm");

    private final ClanService clans;
    private final TradeService trade;
    private final Treasury treasury;
    private final TreasuryMenu menu;
    private final CaravanService caravans;
    private final Stable stable;
    private final CaravanLog log;
    private final TerritoryService territory;
    private final Supplier<HeartholdConfig> config;

    public TradeCommand(ClanService clans, TradeService trade, Treasury treasury, TreasuryMenu menu,
                        CaravanService caravans, Stable stable, CaravanLog log, TerritoryService territory,
                        Supplier<HeartholdConfig> config) {
        this.clans = clans;
        this.trade = trade;
        this.treasury = treasury;
        this.menu = menu;
        this.caravans = caravans;
        this.stable = stable;
        this.log = log;
        this.territory = territory;
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("failures")) {
            failures(sender);
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("log") && sender.hasPermission("hearthold.admin")
                && clans.byName(args[1]).isPresent()) {
            printLog(sender, clans.byName(args[1]).get(), 20);
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.tr("trade.players-only"));
            return true;
        }
        if (args.length == 0) {
            usage(player);
            return true;
        }
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "propose" -> propose(player, args);
                case "offers" -> offers(player);
                case "accept" -> accept(player, args, true);
                case "decline" -> accept(player, args, false);
                case "list" -> list(player);
                case "book" -> book(player, args);
                case "pending" -> pending(player);
                case "equip" -> equip(player, args);
                case "forgive" -> forgive(player, args);
                case "terminate" -> terminate(player, args);
                case "rep" -> rep(player, args);
                case "log" -> printLog(player, requireLeader(player), 15);
                case "treasury" -> menu.open(player, requireInOwnBase(player));
                case "stable" -> stable(player, args);
                default -> usage(player);
            }
        } catch (ClanException e) {
            player.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
        }
        return true;
    }

    // --- Agreements ---

    /** {@code /trade propose <clan> <days> <we give> <they give>}; cargo is MATERIAL:amount, comma-separated, or "-". */
    private void propose(Player player, String[] args) throws ClanException {
        if (args.length < 5) {
            throw new ClanException(Lang.tr("trade.propose.usage"));
        }
        Clan own = requireTrader(player);
        Clan other = clans.byName(args[1]).orElseThrow(() -> new ClanException(Lang.tr("trade.clan-not-found")));
        int days;
        try {
            days = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            throw new ClanException(Lang.tr("trade.propose.period-nan"));
        }
        Map<Material, Integer> give = parseCargo(args[3]);
        Map<Material, Integer> take = parseCargo(args[4]);
        TradeService.Proposal p = trade.propose(own, other, days, give, take);
        ok(player, Lang.tr("trade.propose.sent", p.number(), other.name()));
        ClanService.broadcast(other, Component.text(Lang.tr("trade.propose.incoming", own.name(), p.number(),
                days, cargoText(give), cargoText(take)), NamedTextColor.YELLOW));
        if (other.isDummy()) {
            // A dummy clan (testing) accepts immediately.
            TradeRoute r = trade.accept(other, p.number());
            Component msg = Component.text(Lang.tr("trade.propose.dummy-accepted", other.name(), r.shortId(), names(r)),
                    NamedTextColor.GREEN);
            clans.byId(r.clanA()).ifPresent(c -> ClanService.broadcast(c, msg));
        }
    }

    private void offers(Player player) throws ClanException {
        Clan own = requireClan(player);
        List<TradeService.Proposal> list = trade.proposalsTo(own.id());
        if (list.isEmpty()) {
            info(player, Lang.tr("trade.offers.none"));
        }
        for (TradeService.Proposal p : list) {
            String from = clans.byId(p.from()).map(Clan::name).orElse("?");
            info(player, Lang.tr("trade.offers.entry", p.number(), from, p.periodDays(), linesText(p.lines())));
        }
    }

    private void accept(Player player, String[] args, boolean accept) throws ClanException {
        if (args.length < 2) {
            throw new ClanException(Lang.tr("trade.accept.usage", accept ? "accept" : "decline"));
        }
        Clan own = requireTrader(player);
        int number;
        try {
            number = Integer.parseInt(args[1].replace("№", ""));
        } catch (NumberFormatException e) {
            throw new ClanException(Lang.tr("trade.accept.nan"));
        }
        if (!accept) {
            trade.decline(own, number);
            ok(player, Lang.tr("trade.accept.declined"));
            return;
        }
        TradeRoute r = trade.accept(own, number);
        Component msg = Component.text(Lang.tr("trade.accept.concluded", r.shortId(), names(r)),
                NamedTextColor.GREEN);
        clans.byId(r.clanA()).ifPresent(c -> ClanService.broadcast(c, msg));
        clans.byId(r.clanB()).ifPresent(c -> ClanService.broadcast(c, msg));
    }

    private void list(Player player) throws ClanException {
        Clan own = requireClan(player);
        var routes = trade.routesOf(own.id());
        if (routes.isEmpty()) {
            info(player, Lang.tr("trade.list.none"));
        }
        for (TradeRoute r : routes) {
            String partner = clans.byId(r.partnerOf(own.id())).map(Clan::name).orElse("?");
            String state = r.status() == TradeRoute.Status.ACTIVE
                    ? Lang.tr("trade.list.period", r.currentPeriod() + 1)
                    : Lang.tr("trade.list.terminated", TradeService.Reason.valueOf(r.reason()).title());
            info(player, Lang.tr("trade.list.entry", r.shortId(), partner, r.periodDays(), state,
                    linesText(r.lines())));
        }
    }

    /** Trade route book: per-period schedule with statuses. Members of either clan can open it. */
    private void book(Player player, String[] args) throws ClanException {
        if (args.length < 2) {
            throw new ClanException(Lang.tr("trade.book.usage"));
        }
        Clan own = requireClan(player);
        TradeRoute r = trade.find(own.id(), args[1]);
        List<Component> pages = new ArrayList<>();
        Component title = Component.text(Lang.tr("trade.book.title", r.shortId()) + "\n", NamedTextColor.DARK_BLUE)
                .append(Component.text(names(r) + "\n" + Lang.tr("trade.book.every", r.periodDays()) + "\n",
                        NamedTextColor.BLACK));
        if (r.status() == TradeRoute.Status.TERMINATED) {
            title = title.append(Component.text(Lang.tr("trade.book.terminated", TradeService.Reason.valueOf(r.reason()).title()) + "\n",
                    NamedTextColor.DARK_RED));
        }
        pages.add(title.append(Component.text("\n" + Lang.tr("trade.book.schedule-next"), NamedTextColor.DARK_GRAY)));
        int current = r.currentPeriod();
        int from = Math.max(0, current - 8);
        for (int p = from; p <= current + 1; p++) {
            ZoneId zone = ZoneId.systemDefault();
            String range = DAY.format(Instant.ofEpochMilli(r.periodStart(p)).atZone(zone)) + "–"
                    + DAY.format(Instant.ofEpochMilli(r.periodStart(p + 1) - 1).atZone(zone));
            Component page = Component.text(Lang.tr("trade.book.period", p + 1) + "\n" + range + "\n\n", NamedTextColor.DARK_BLUE);
            for (TradeRoute.Line l : r.lines()) {
                TradeRoute.RowStatus s = r.rowStatus(p, l);
                String dir = clans.byId(l.fromClan()).map(Clan::name).orElse("?") + " → "
                        + clans.byId(r.partnerOf(l.fromClan())).map(Clan::name).orElse("?");
                int done = r.delivered(p, l.fromClan(), l.material());
                page = page.append(Component.text(l.amount() + " " + l.material().name().toLowerCase(Locale.ROOT)
                                + "\n" + dir + "\n", NamedTextColor.BLACK))
                        .append(Component.text(s.title() + (s == TradeRoute.RowStatus.IN_PROGRESS
                                || s == TradeRoute.RowStatus.OVERDUE ? " (" + done + "/" + l.amount() + ")" : "")
                                + "\n\n", color(s)));
            }
            pages.add(page);
        }
        player.openBook(Book.book(Component.text(Lang.tr("trade.book.name")), Component.text("Hearthold"), pages));
    }

    private void pending(Player player) throws ClanException {
        Clan own = requireClan(player);
        int n = 0;
        for (TradeRoute r : trade.routesOf(own.id())) {
            Map<Material, Integer> left = r.remaining(own.id());
            left.values().removeIf(v -> v <= 0);
            if (left.isEmpty()) {
                continue;
            }
            n++;
            String partner = clans.byId(r.partnerOf(own.id())).map(Clan::name).orElse("?");
            String inTreasury = left.keySet().stream().map(m -> m.name().toLowerCase(Locale.ROOT) + " "
                    + treasury.amount(own.id(), m)).reduce((a, b) -> a + ", " + b).orElse("");
            info(player, Lang.tr("trade.pending.entry", n, r.shortId(), partner, cargoText(left), inTreasury));
        }
        if (n == 0) {
            info(player, Lang.tr("trade.pending.none"));
        } else {
            info(player, Lang.tr("trade.pending.hint"));
        }
    }

    private void equip(Player player, String[] args) throws ClanException {
        if (args.length < 2) {
            throw new ClanException(Lang.tr("trade.equip.usage"));
        }
        Clan own = requireInOwnBase(player);
        if (!own.canTrade(player.getUniqueId())) {
            throw new ClanException(Lang.tr("trade.equip.no-rights"));
        }
        TradeRoute r = trade.find(own.id(), args[1]);
        Map<Material, Integer> cargo = caravans.equip(player, own, r);
        ok(player, Lang.tr("trade.equip.done", CaravanService.describe(cargo),
                config.get().trade().standSeconds()));
    }

    private void forgive(Player player, String[] args) throws ClanException {
        if (args.length < 3) {
            throw new ClanException(Lang.tr("trade.forgive.usage"));
        }
        Clan own = requireTrader(player);
        TradeRoute r = trade.find(own.id(), args[1]);
        int period;
        try {
            period = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            throw new ClanException(Lang.tr("trade.forgive.nan"));
        }
        trade.forgive(own, r, period);
        Component msg = Component.text(Lang.tr("trade.forgive.done", own.name(), r.shortId(), period),
                NamedTextColor.GREEN);
        clans.byId(r.clanA()).ifPresent(c -> ClanService.broadcast(c, msg));
        clans.byId(r.clanB()).ifPresent(c -> ClanService.broadcast(c, msg));
    }

    private void terminate(Player player, String[] args) throws ClanException {
        if (args.length < 3) {
            throw new ClanException(Lang.tr("trade.terminate.usage"));
        }
        Clan own = requireLeader(player);
        TradeRoute r = trade.find(own.id(), args[1]);
        TradeService.Reason reason;
        try {
            reason = TradeService.Reason.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ClanException(Lang.tr("trade.terminate.reasons"));
        }
        trade.terminate(own, r, reason);
        Component msg = Component.text(Lang.tr("trade.terminate.done", own.name(), r.shortId(), reason.title()),
                NamedTextColor.GOLD);
        clans.byId(r.clanA()).ifPresent(c -> ClanService.broadcast(c, msg));
        clans.byId(r.clanB()).ifPresent(c -> ClanService.broadcast(c, msg));
    }

    private void failures(CommandSender sender) {
        var list = trade.failures(15);
        sender.sendMessage(Component.text(Lang.tr("trade.failures.header"), NamedTextColor.GOLD));
        if (list.isEmpty()) {
            sender.sendMessage(Component.text("  " + Lang.tr("trade.failures.none"), NamedTextColor.GRAY));
        }
        for (TradeService.Failure f : list) {
            sender.sendMessage(Component.text("  " + Lang.tr("trade.failures.entry",
                    TIME.format(Instant.ofEpochMilli(f.at()).atZone(ZoneId.systemDefault())), f.offender(), f.victim()),
                    NamedTextColor.GRAY));
        }
    }

    private void rep(Player player, String[] args) throws ClanException {
        Clan clan = args.length >= 2
                ? clans.byName(args[1]).orElseThrow(() -> new ClanException(Lang.tr("trade.clan-not-found")))
                : requireClan(player);
        info(player, Lang.tr("trade.rep", clan.name(), trade.reputation(clan.id())));
    }

    private void printLog(CommandSender sender, Clan clan, int limit) {
        sender.sendMessage(Component.text(Lang.tr("trade.log.header", clan.name()), NamedTextColor.GOLD));
        for (CaravanLog.Entry e : log.recent(clan.id(), limit)) {
            sender.sendMessage(Component.text("  " + TIME.format(Instant.ofEpochMilli(e.at()).atZone(ZoneId.systemDefault()))
                    + " " + e.player() + " — " + e.action() + ": " + e.details(), NamedTextColor.GRAY));
        }
    }

    // --- Stable ---

    private void stable(Player player, String[] args) throws ClanException {
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
        Clan own = requireClan(player);
        switch (action) {
            case "add" -> {
                requireInOwnBase(player);
                requireTrader(player);
                Stable.Entry e = stable.add(own, player);
                log.add(own.id(), player.getName(), Lang.tr("trade.log.mount-stored"), e.kind().displayName());
                ok(player, Lang.tr("trade.stable.added"));
            }
            case "take" -> {
                requireInOwnBase(player);
                requireTrader(player);
                Stable.Entry e = pick(own, args);
                stable.take(e, player.getLocation(), player);
                log.add(own.id(), player.getName(), Lang.tr("trade.log.mount-taken"), e.kind().displayName());
                ok(player, Lang.tr("trade.stable.taken", config.get().trade().standSeconds()));
            }
            case "upgrade" -> {
                requireInOwnBase(player);
                requireTrader(player);
                Stable.Entry e = pick(own, args);
                Map<Material, Integer> cost = config.get().trade().wagonCost();
                for (var c : cost.entrySet()) {
                    if (treasury.amount(own.id(), c.getKey()) < c.getValue()) {
                        throw new ClanException(Lang.tr("trade.stable.upgrade-short", cargoText(cost)));
                    }
                }
                stable.upgrade(e);
                cost.forEach((m, a) -> treasury.take(own.id(), m, a));
                log.add(own.id(), player.getName(), Lang.tr("trade.log.mount-upgraded"), cargoText(cost));
                ok(player, Lang.tr("trade.stable.upgraded"));
            }
            default -> {
                List<Stable.Entry> list = stable.of(own.id());
                if (list.isEmpty()) {
                    info(player, Lang.tr("trade.stable.empty"));
                }
                for (int i = 0; i < list.size(); i++) {
                    Stable.Entry e = list.get(i);
                    info(player, Lang.tr("trade.stable.entry", i + 1, e.kind().displayName(),
                            String.format(Locale.ROOT, "%.3f", e.speed()),
                            Lang.tr(e.status() == Stable.Status.OUT ? "trade.stable.status-out" : "trade.stable.status-home")));
                }
                info(player, Lang.tr("trade.stable.usage", cargoText(config.get().trade().wagonCost())));
            }
        }
    }

    private Stable.Entry pick(Clan own, String[] args) throws ClanException {
        List<Stable.Entry> list = stable.of(own.id());
        try {
            int n = Integer.parseInt(args[2]);
            if (n >= 1 && n <= list.size()) {
                return list.get(n - 1);
            }
        } catch (RuntimeException ignored) {
            // fall through to the error below
        }
        throw new ClanException(Lang.tr("trade.stable.pick"));
    }

    // --- Helpers ---

    private Map<Material, Integer> parseCargo(String spec) throws ClanException {
        Map<Material, Integer> result = new LinkedHashMap<>();
        if (spec.equals("-")) {
            return result;
        }
        for (String part : spec.split(",")) {
            String[] kv = part.split(":");
            Material m = kv.length == 2 ? Material.matchMaterial(kv[0]) : null;
            if (m == null || !m.isItem() || m.isAir()) {
                throw new ClanException(Lang.tr("trade.cargo.bad", part));
            }
            int amount;
            try {
                amount = Integer.parseInt(kv[1]);
            } catch (NumberFormatException e) {
                throw new ClanException(Lang.tr("trade.cargo.nan", part));
            }
            if (amount < 1 || amount > 100_000) {
                throw new ClanException(Lang.tr("trade.cargo.range", part));
            }
            if (result.containsKey(m)) {
                throw new ClanException(Lang.tr("trade.cargo.duplicate", m.name().toLowerCase(Locale.ROOT)));
            }
            result.put(m, amount);
        }
        return result;
    }

    private String names(TradeRoute r) {
        return clans.byId(r.clanA()).map(Clan::name).orElse("?") + " ⇄ " + clans.byId(r.clanB()).map(Clan::name).orElse("?");
    }

    private String linesText(List<TradeRoute.Line> lines) {
        return lines.stream().map(l -> Lang.tr("trade.line", clans.byId(l.fromClan()).map(Clan::name).orElse("?"),
                l.amount(), l.material().name().toLowerCase(Locale.ROOT)))
                .reduce((a, b) -> a + "; " + b).orElse("—");
    }

    private static String cargoText(Map<Material, Integer> cargo) {
        return cargo.isEmpty() ? Lang.tr("trade.cargo.nothing") : CaravanService.describe(cargo);
    }

    private static NamedTextColor color(TradeRoute.RowStatus s) {
        return switch (s) {
            case DONE -> NamedTextColor.DARK_GREEN;
            case FORGIVEN -> NamedTextColor.DARK_AQUA;
            case IN_PROGRESS -> NamedTextColor.GOLD;
            case OVERDUE -> NamedTextColor.DARK_RED;
            case FUTURE -> NamedTextColor.DARK_GRAY;
        };
    }

    private Clan requireClan(Player player) throws ClanException {
        return clans.ofPlayer(player.getUniqueId()).orElseThrow(() -> new ClanException(Lang.tr("trade.no-clan")));
    }

    private Clan requireTrader(Player player) throws ClanException {
        Clan clan = requireClan(player);
        if (!clan.canTrade(player.getUniqueId())) {
            throw new ClanException(Lang.tr("trade.no-trader"));
        }
        return clan;
    }

    private Clan requireLeader(Player player) throws ClanException {
        Clan clan = requireClan(player);
        if (!clan.isLeader(player.getUniqueId())) {
            throw new ClanException(Lang.tr("trade.no-leader"));
        }
        return clan;
    }

    private Clan requireInOwnBase(Player player) throws ClanException {
        Clan clan = requireClan(player);
        boolean inBase = territory.claimOf(player.getLocation().getBlock())
                .map(c -> c.id().equals(clan.id())).orElse(false);
        if (!inBase) {
            throw new ClanException(Lang.tr("trade.treasury.base-only"));
        }
        return clan;
    }

    private static void ok(Player player, String text) {
        player.sendMessage(Component.text(text, NamedTextColor.GREEN));
    }

    private static void info(Player player, String text) {
        player.sendMessage(Component.text(text, NamedTextColor.GRAY));
    }

    private static void usage(Player player) {
        info(player, Lang.tr("trade.usage"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return SUBS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && List.of("propose", "rep").contains(sub)) {
            return clans.all().stream().map(Clan::name)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && List.of("book", "equip", "forgive", "terminate").contains(sub)
                && sender instanceof Player p) {
            return clans.ofPlayer(p.getUniqueId()).map(c -> trade.routesOf(c.id()).stream()
                    .map(TradeRoute::shortId).toList()).orElse(List.of());
        }
        if (args.length == 3 && sub.equals("terminate")) {
            return Arrays.stream(TradeService.Reason.values()).map(r -> r.name().toLowerCase(Locale.ROOT))
                    .filter(n -> n.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && sub.equals("stable")) {
            return List.of("add", "take", "upgrade").stream().filter(s -> s.startsWith(args[1])).toList();
        }
        if (args.length >= 4 && sub.equals("propose")) {
            String last = args[args.length - 1];
            String prefix = last.contains(",") ? last.substring(0, last.lastIndexOf(',') + 1) : "";
            String tail = last.substring(prefix.length()).toLowerCase(Locale.ROOT);
            if (tail.contains(":")) {
                return List.of();
            }
            return Arrays.stream(Material.values()).filter(m -> m.isItem() && !m.isAir())
                    .map(m -> m.name().toLowerCase(Locale.ROOT)).filter(n -> n.startsWith(tail))
                    .limit(30).map(n -> prefix + n + ":").toList();
        }
        return List.of();
    }
}
