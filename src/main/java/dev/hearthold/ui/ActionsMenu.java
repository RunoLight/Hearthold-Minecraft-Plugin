package dev.hearthold.ui;

import dev.hearthold.base.TerritoryService;
import dev.hearthold.clan.AllyService;
import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanService;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.event.EventService;
import dev.hearthold.i18n.Lang;
import dev.hearthold.plot.ChestAccess;
import dev.hearthold.plot.Plot;
import dev.hearthold.plot.PlotService;
import dev.hearthold.trade.Stable;
import dev.hearthold.trade.TradeRoute;
import dev.hearthold.trade.TradeService;
import dev.hearthold.war.War;
import dev.hearthold.war.WarService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Buttons for everything that used to be command-only: no-clan screen, clan management and diplomacy,
 * war, trade, stable, plots, events. Actions run the same commands as in chat, so checks and messages
 * are identical. Text input (name, cargo) goes through {@link ChatPrompt}.
 */
public final class ActionsMenu {

    private final ClanService clans;
    private final AllyService allies;
    private final TerritoryService territory;
    private final WarService wars;
    private final TradeService trade;
    private final Stable stable;
    private final PlotService plots;
    private final EventService events;
    private final ChatPrompt prompt;
    private final Supplier<HeartholdConfig> config;

    public ActionsMenu(ClanService clans, AllyService allies, TerritoryService territory, WarService wars,
                       TradeService trade, Stable stable, PlotService plots, EventService events, ChatPrompt prompt,
                       Supplier<HeartholdConfig> config) {
        this.clans = clans;
        this.allies = allies;
        this.territory = territory;
        this.wars = wars;
        this.trade = trade;
        this.stable = stable;
        this.plots = plots;
        this.events = events;
        this.prompt = prompt;
        this.config = config;
    }

    /** Special state of the clan's base (abandoned, heart being removed) or null. */
    private java.util.function.Function<UUID, String> clanStatus = id -> null;

    public void setClanStatus(java.util.function.Function<UUID, String> clanStatus) {
        this.clanStatus = clanStatus;
    }

    public ChatPrompt prompt() {
        return prompt;
    }

    private static String back() {
        return Lang.tr("menu.back");
    }

    // --- No clan ---

    public void openNoClan(Player player, Consumer<Player> back) {
        Menu m = new Menu(3, Component.text(Lang.tr("menu.noclan.title")));
        m.set(11, Menu.icon(Material.WHITE_BANNER, Lang.tr("menu.noclan.create"), Lang.tr("menu.noclan.create.lore1"),
                        Lang.tr("menu.noclan.create.lore2")),
                p -> prompt.ask(p, Lang.tr("menu.noclan.create.ask"), Lang.tr("menu.noclan.create.hint"), (q, name) -> {
                    if (name.contains(" ")) {
                        q.sendMessage(Component.text(Lang.tr("menu.noclan.create.one-word"), NamedTextColor.RED));
                        return;
                    }
                    q.performCommand("clan create " + name);
                }));
        Optional<Clan> invited = clans.invitedTo(player.getUniqueId()).flatMap(clans::byId);
        m.set(13, invited.map(c -> Menu.icon(Material.LIME_DYE, Lang.tr("menu.noclan.accept"),
                        Lang.tr("menu.clan-quoted", c.name()), Lang.tr("menu.members", c.members().size()),
                        Lang.tr("menu.noclan.accept.click")))
                .orElse(Menu.icon(Material.GRAY_DYE, Lang.tr("menu.noclan.no-invites"),
                        Lang.tr("menu.noclan.no-invites.lore1"), Lang.tr("menu.noclan.no-invites.lore2"))),
                invited.isPresent() ? run("clan accept") : null);
        m.set(15, Menu.icon(Material.COMPASS, Lang.tr("menu.noclan.server-clans"), Lang.tr("menu.noclan.server-clans.lore")),
                p -> clanPicker(p, Lang.tr("menu.noclan.server-clans"), q -> openNoClan(q, back), null));
        if (back != null) {
            m.set(18, Menu.icon(Material.ARROW, back()), back);
        }
        m.open(player);
    }

    // --- Managing your own clan ---

    public void openManage(Player player, Consumer<Player> back) {
        Optional<Clan> own = clans.ofPlayer(player.getUniqueId());
        if (own.isEmpty()) {
            openNoClan(player, back);
            return;
        }
        Clan clan = own.get();
        boolean leader = clan.isLeader(player.getUniqueId());
        Consumer<Player> self = p -> openManage(p, back);
        Menu m = new Menu(4, Component.text(Lang.tr("menu.manage.title", clan.name())));
        m.set(10, Menu.icon(Material.BOOK, Lang.tr("menu.manage.info"), Lang.tr("menu.manage.info.lore1"),
                        Lang.tr("menu.manage.info.lore2")),
                run("clan info"));
        if (territory.heartOf(clan.id()).isEmpty()) {
            var n = config.get().newbie();
            m.set(11, Menu.icon(config.get().heart().material(), Lang.tr("menu.manage.heart"),
                    Lang.tr("menu.manage.heart.price", String.format(Locale.ROOT, "%.0f", n.heartCost()),
                            String.format(Locale.ROOT, "%.0f", n.firstHeartCost())),
                    leader ? Lang.tr("menu.click-get") : Lang.tr("menu.leader-gets")), leader ? run("clan heart") : null);
        }
        List<String> allyLore = new ArrayList<>();
        allies.alliesOf(clan.id()).forEach(id -> clans.byId(id).ifPresent(c -> allyLore.add(c.name())));
        if (allyLore.isEmpty()) {
            allyLore.add(Lang.tr("menu.manage.no-allies"));
        }
        allyLore.add(Lang.tr("menu.manage.diplomacy.lore1"));
        allyLore.add(Lang.tr("menu.manage.diplomacy.lore2"));
        m.set(12, Menu.icon(Material.CYAN_BANNER, Lang.tr("menu.manage.diplomacy"), allyLore),
                p -> clanPicker(p, Lang.tr("menu.manage.diplomacy"), self, c -> clanCard(p, c, q -> openManage(q, back))));
        m.set(13, Menu.icon(Material.IRON_SWORD, Lang.tr("menu.war.title")), p -> warMenu(p, self));
        m.set(14, Menu.icon(Material.CHEST_MINECART, Lang.tr("menu.trade.title"), Lang.tr("menu.manage.trade.lore1"),
                        Lang.tr("menu.manage.trade.lore2")),
                p -> tradeMenu(p, self));
        m.set(15, Menu.icon(Material.OAK_DOOR, Lang.tr("menu.plots.title"), Lang.tr("menu.manage.plots.lore")),
                p -> plotMenu(p, self));
        m.set(16, Menu.icon(Material.BARRIER, Lang.tr("menu.manage.leave"),
                        leader ? Lang.tr("menu.manage.leave.leader") : Lang.tr("menu.next-confirm")),
                p -> confirm(p, Lang.tr("menu.manage.leave.confirm", clan.name()),
                        List.of(Lang.tr("menu.manage.leave.confirm.lore1"), Lang.tr("menu.manage.leave.confirm.lore2")),
                        run("clan leave"), self));
        if (back != null) {
            m.set(27, Menu.icon(Material.ARROW, back()), back);
        }
        m.open(player);
    }

    // --- Clan list and clan card ---

    /** List of clans (except your own). onPick null: a click shows clan info. */
    public void clanPicker(Player player, String title, Consumer<Player> back, Consumer<Clan> onPick) {
        UUID own = clans.ofPlayer(player.getUniqueId()).map(Clan::id).orElse(null);
        Menu m = new Menu(6, Component.text(title));
        int slot = 0;
        for (Clan c : clans.all()) {
            if (c.id().equals(own)) {
                continue;
            }
            if (slot >= 45) {
                break;
            }
            List<String> lore = new ArrayList<>();
            lore.add(Lang.tr("menu.members", c.members().size()));
            lore.add(Lang.tr("menu.reputation", trade.reputation(c.id())));
            String status = clanStatus.apply(c.id());
            if (status != null) {
                lore.add(status);
            }
            if (own != null) {
                if (allies.areAllies(own, c.id())) {
                    lore.add(Lang.tr("menu.picker.ally"));
                }
                if (wars.ongoing().stream().anyMatch(w -> w.between(own, c.id()))) {
                    lore.add(Lang.tr("menu.picker.at-war"));
                }
            }
            lore.add(onPick == null ? Lang.tr("menu.picker.click-info") : Lang.tr("menu.picker.click-actions"));
            boolean atWar = own != null && wars.ongoing().stream().anyMatch(w -> w.between(own, c.id()));
            m.set(slot++, Menu.icon(atWar ? Material.RED_BANNER : Material.WHITE_BANNER, c.name(), lore),
                    onPick == null ? run("clan info " + c.name()) : p -> onPick.accept(c));
        }
        if (slot == 0) {
            m.set(22, Menu.icon(Material.PAPER, Lang.tr("menu.picker.none")), null);
        }
        m.set(49, Menu.icon(Material.ARROW, back()), back);
        m.open(player);
    }

    /** Your clan's actions towards another clan. */
    public void clanCard(Player player, Clan other, Consumer<Player> back) {
        Optional<Clan> ownOpt = clans.ofPlayer(player.getUniqueId());
        if (ownOpt.isEmpty()) {
            return;
        }
        Clan own = ownOpt.get();
        boolean leader = own.isLeader(player.getUniqueId());
        boolean trader = own.canTrade(player.getUniqueId());
        Consumer<Player> self = p -> clanCard(p, other, back);
        Menu m = new Menu(3, Component.text(Lang.tr("menu.clan-quoted", other.name())));
        m.set(10, Menu.icon(Material.BOOK, Lang.tr("menu.card.info"), Lang.tr("menu.card.info.lore")),
                run("clan info " + other.name()));
        m.set(11, Menu.icon(Material.NAME_TAG, Lang.tr("menu.reputation", trade.reputation(other.id()))), null);
        boolean allied = allies.areAllies(own.id(), other.id());
        if (allied) {
            m.set(12, Menu.icon(Material.CYAN_DYE, Lang.tr("menu.card.unally"),
                            leader ? Lang.tr("menu.next-confirm") : Lang.tr("menu.leader-decides")),
                    leader ? p -> confirm(p, Lang.tr("menu.card.unally.confirm", other.name()), List.of(),
                            run("clan unally " + other.name()), self) : null);
        } else {
            m.set(12, Menu.icon(Material.LIGHT_BLUE_DYE, Lang.tr("menu.card.ally"), Lang.tr("menu.card.ally.lore1"),
                            Lang.tr("menu.card.ally.lore2"), leader ? Lang.tr("menu.card.ally.click") : Lang.tr("menu.leader-decides")),
                    leader ? run("clan ally " + other.name()) : null);
        }
        boolean atWar = wars.ongoing().stream().anyMatch(w -> w.between(own.id(), other.id()));
        if (atWar) {
            boolean attacker = wars.ongoing().stream()
                    .anyMatch(w -> w.between(own.id(), other.id()) && w.attacker().equals(own.id()));
            m.set(14, Menu.icon(Material.WHITE_BANNER, Lang.tr("menu.card.peace"), Lang.tr("menu.card.peace.lore1"),
                            Lang.tr("menu.card.peace.lore2"), leader ? Lang.tr("menu.card.peace.click") : Lang.tr("menu.leader-decides")),
                    leader ? run("war peace " + other.name()) : null);
            if (attacker) {
                m.set(15, Menu.icon(Material.LEATHER_BOOTS, Lang.tr("menu.card.retreat"), Lang.tr("menu.card.retreat.lore"),
                                leader ? Lang.tr("menu.next-confirm") : Lang.tr("menu.leader-decides")),
                        leader ? p -> confirm(p, Lang.tr("menu.card.retreat.confirm", other.name()), List.of(),
                                run("war end " + other.name()), self) : null);
            }
        } else {
            m.set(14, Menu.icon(Material.IRON_SWORD, Lang.tr("menu.card.declare"), Lang.tr("menu.card.declare.lore1"),
                            Lang.tr("menu.card.declare.lore2"), leader ? Lang.tr("menu.next-confirm") : Lang.tr("menu.leader-decides")),
                    leader ? p -> confirm(p, Lang.tr("menu.card.declare.confirm", other.name()),
                            List.of(Lang.tr("menu.card.declare.confirm.lore1"), Lang.tr("menu.card.declare.confirm.lore2")),
                            run("war declare " + other.name()), self) : null);
        }
        m.set(16, Menu.icon(Material.CHEST_MINECART, Lang.tr("menu.card.propose-route"),
                        trader ? Lang.tr("menu.card.propose-route.lore") : Lang.tr("menu.leader-and-trusted")),
                trader ? p -> proposeTrade(p, other) : null);
        m.set(18, Menu.icon(Material.ARROW, back()), back);
        m.open(player);
    }

    // --- War ---

    public void warMenu(Player player, Consumer<Player> back) {
        Optional<Clan> ownOpt = clans.ofPlayer(player.getUniqueId());
        if (ownOpt.isEmpty()) {
            openNoClan(player, back);
            return;
        }
        Clan own = ownOpt.get();
        boolean leader = own.isLeader(player.getUniqueId());
        Consumer<Player> self = p -> warMenu(p, back);
        Menu m = new Menu(4, Component.text(Lang.tr("menu.war.title")));
        m.set(1, Menu.icon(Material.CLOCK, Lang.tr("menu.war.status"), Lang.tr("menu.war.status.lore1"),
                Lang.tr("menu.war.status.lore2")), run("war status"));
        m.set(2, Menu.icon(Material.WRITABLE_BOOK, Lang.tr("menu.war.journal"), Lang.tr("menu.war.journal.lore")),
                run("war journal"));
        m.set(3, Menu.icon(Material.IRON_BARS, Lang.tr("menu.war.vassals"), Lang.tr("menu.war.vassals.lore")),
                run("war vassals"));
        m.set(5, Menu.icon(Material.IRON_SWORD, Lang.tr("menu.war.declare"), Lang.tr("menu.war.declare.lore")),
                p -> clanPicker(p, Lang.tr("menu.war.pick-title"), self, c -> clanCard(p, c, self)));
        m.set(6, Menu.icon(Material.BUNDLE, Lang.tr("menu.war.tribute"), Lang.tr("menu.war.tribute.lore"),
                        own.canTrade(player.getUniqueId()) ? Lang.tr("menu.war.tribute.click") : Lang.tr("menu.leader-and-trusted")),
                run("war tribute"));
        var ws = config.get().war();
        String patronCost = String.format(Locale.ROOT, "%.0f", ws.patronageCost());
        m.set(7, Menu.icon(config.get().heart().material(), Lang.tr("menu.war.patron"),
                        Lang.tr("menu.war.patron.price", patronCost),
                        Lang.tr("menu.war.patron.lore1"), Lang.tr("menu.war.patron.lore2"),
                        leader ? Lang.tr("menu.next-confirm") : Lang.tr("menu.war.patron.leader-buys")),
                leader ? p -> confirm(p, Lang.tr("menu.war.patron.confirm"),
                        List.of(Lang.tr("menu.war.patron.confirm.lore", patronCost)),
                        run("war patron"), self) : null);
        int slot = 18;
        long now = System.currentTimeMillis();
        for (War w : wars.ongoing()) {
            if (!w.involves(own.id()) || slot >= 27) {
                continue;
            }
            UUID enemy = w.attacker().equals(own.id()) ? w.defender() : w.attacker();
            Optional<Clan> enemyClan = clans.byId(enemy);
            m.set(slot++, Menu.icon(Material.RED_BANNER, w.attackerName() + " → " + w.defenderName(),
                            w.isActive(now) ? Lang.tr("menu.war.active") : Lang.tr("menu.war.preparing"),
                            Lang.tr("menu.war.click-peace")),
                    enemyClan.isPresent() ? p -> clanCard(p, enemyClan.get(), self) : null);
        }
        m.set(27, Menu.icon(Material.ARROW, back()), back);
        m.open(player);
    }

    // --- Trade ---

    public void tradeMenu(Player player, Consumer<Player> back) {
        Optional<Clan> ownOpt = clans.ofPlayer(player.getUniqueId());
        if (ownOpt.isEmpty()) {
            openNoClan(player, back);
            return;
        }
        Clan own = ownOpt.get();
        boolean trader = own.canTrade(player.getUniqueId());
        Consumer<Player> self = p -> tradeMenu(p, back);
        Menu m = new Menu(6, Component.text(Lang.tr("menu.trade.title")));
        m.set(0, Menu.icon(Material.WRITABLE_BOOK, Lang.tr("menu.trade.propose"), Lang.tr("menu.trade.propose.lore"),
                        trader ? Lang.tr("menu.trade.propose.click") : Lang.tr("menu.leader-and-trusted")),
                trader ? p -> clanPicker(p, Lang.tr("menu.trade.pick-title"), self, c -> proposeTrade(p, c)) : null);
        m.set(1, Menu.icon(Material.HOPPER, Lang.tr("menu.trade.pending"), Lang.tr("menu.trade.pending.lore")),
                run("trade pending"));
        m.set(2, Menu.icon(Material.ENDER_CHEST, Lang.tr("menu.trade.treasury"), Lang.tr("menu.trade.treasury.lore")),
                run("trade treasury"));
        m.set(3, Menu.icon(Material.SADDLE, Lang.tr("menu.trade.stable"), Lang.tr("menu.trade.stable.lore")),
                p -> stableMenu(p, self));
        m.set(4, Menu.icon(Material.NAME_TAG, Lang.tr("menu.trade.reputation", trade.reputation(own.id()))), null);
        m.set(5, Menu.icon(Material.PAPER, Lang.tr("menu.trade.log"), own.isLeader(player.getUniqueId())
                ? Lang.tr("menu.trade.log.lore") : Lang.tr("menu.trade.log.leader")),
                own.isLeader(player.getUniqueId()) ? run("trade log") : null);
        m.set(6, Menu.icon(Material.SKELETON_SKULL, Lang.tr("menu.trade.failures"), Lang.tr("menu.trade.failures.lore")),
                run("trade failures"));

        // Row 2: proposals to us.
        m.set(9, Menu.icon(Material.BELL, Lang.tr("menu.trade.proposals")), null);
        int slot = 10;
        for (TradeService.Proposal pr : trade.proposalsTo(own.id())) {
            if (slot >= 18) {
                break;
            }
            String from = clans.byId(pr.from()).map(Clan::name).orElse("?");
            List<String> lore = new ArrayList<>();
            lore.add(Lang.plural("menu.every-days", pr.periodDays()));
            lore.addAll(linesLore(pr.lines()));
            lore.add(trader ? Lang.tr("menu.trade.proposal.click") : Lang.tr("menu.trade.proposal.decide"));
            m.set(slot++, Menu.icon(Material.MAP, Lang.tr("menu.trade.proposal.name", pr.number(), from), lore),
                    trader ? p -> proposalCard(p, pr, self) : null);
        }
        if (slot == 10) {
            m.set(10, Menu.icon(Material.GRAY_DYE, Lang.tr("menu.trade.no-proposals")), null);
        }

        // Rows 3-5: routes.
        m.set(18, Menu.icon(Material.RAIL, Lang.tr("menu.trade.routes")), null);
        slot = 19;
        for (TradeRoute r : trade.routesOf(own.id())) {
            if (slot >= 45) {
                break;
            }
            String partner = clans.byId(r.partnerOf(own.id())).map(Clan::name).orElse("?");
            boolean active = r.status() == TradeRoute.Status.ACTIVE;
            List<String> lore = new ArrayList<>();
            lore.add(Lang.plural("menu.every-days", r.periodDays()) + ", " + (active
                    ? Lang.tr("menu.trade.route.period", r.currentPeriod() + 1) : Lang.tr("menu.trade.route.terminated")));
            lore.addAll(linesLore(r.lines()));
            lore.add(Lang.tr("menu.trade.route.click"));
            m.set(slot++, Menu.icon(active ? Material.CHEST_MINECART : Material.MINECART,
                    Lang.tr("menu.trade.route.name", r.shortId(), partner), lore), p -> routeCard(p, r, self));
        }
        if (slot == 19) {
            m.set(19, Menu.icon(Material.GRAY_DYE, Lang.tr("menu.trade.no-routes"), Lang.tr("menu.trade.no-routes.lore")), null);
        }
        m.set(45, Menu.icon(Material.ARROW, back()), back);
        m.open(player);
    }

    private List<String> linesLore(List<TradeRoute.Line> lines) {
        List<String> lore = new ArrayList<>();
        for (TradeRoute.Line l : lines) {
            lore.add(Lang.tr("menu.trade.line", clans.byId(l.fromClan()).map(Clan::name).orElse("?"), l.amount(),
                    l.material().name().toLowerCase(Locale.ROOT)));
        }
        return lore;
    }

    private void proposalCard(Player player, TradeService.Proposal pr, Consumer<Player> back) {
        String from = clans.byId(pr.from()).map(Clan::name).orElse("?");
        Menu m = new Menu(3, Component.text(Lang.tr("menu.trade.proposal.title", pr.number(), from)));
        List<String> lore = new ArrayList<>(linesLore(pr.lines()));
        lore.add(0, Lang.plural("menu.every-days", pr.periodDays()));
        m.set(4, Menu.icon(Material.MAP, Lang.tr("menu.trade.proposal.terms"), lore), null);
        m.set(11, Menu.icon(Material.LIME_CONCRETE, Lang.tr("menu.trade.proposal.accept")), run("trade accept " + pr.number()));
        m.set(15, Menu.icon(Material.RED_CONCRETE, Lang.tr("menu.trade.proposal.decline")), run("trade decline " + pr.number()));
        m.set(18, Menu.icon(Material.ARROW, back()), back);
        m.open(player);
    }

    private void routeCard(Player player, TradeRoute r, Consumer<Player> back) {
        Optional<Clan> ownOpt = clans.ofPlayer(player.getUniqueId());
        if (ownOpt.isEmpty()) {
            return;
        }
        Clan own = ownOpt.get();
        boolean trader = own.canTrade(player.getUniqueId());
        boolean leader = own.isLeader(player.getUniqueId());
        boolean active = r.status() == TradeRoute.Status.ACTIVE;
        Consumer<Player> self = p -> routeCard(p, r, back);
        String id = r.shortId();
        Menu m = new Menu(3, Component.text(Lang.tr("menu.route.title", id)));
        m.set(10, Menu.icon(Material.WRITTEN_BOOK, Lang.tr("menu.route.book"), Lang.tr("menu.route.book.lore")),
                run("trade book " + id));
        if (active) {
            m.set(12, Menu.icon(Material.CHEST, Lang.tr("menu.route.equip"), Lang.tr("menu.route.equip.lore1"),
                            Lang.tr("menu.route.equip.lore2"), trader ? Lang.tr("menu.route.equip.click") : Lang.tr("menu.leader-and-trusted")),
                    trader ? run("trade equip " + id) : null);
            m.set(14, Menu.icon(Material.HONEY_BOTTLE, Lang.tr("menu.route.forgive"), Lang.tr("menu.route.forgive.lore"),
                            trader ? Lang.tr("menu.route.forgive.click") : Lang.tr("menu.leader-and-trusted")),
                    trader ? p -> prompt.ask(p, Lang.tr("menu.route.forgive.ask", id),
                            Lang.tr("menu.route.forgive.hint"), (q, text) -> q.performCommand(
                                    "trade forgive " + id + " " + text.replaceAll("\\s+", ""))) : null);
            m.set(16, Menu.icon(Material.SHEARS, Lang.tr("menu.route.terminate"),
                            leader ? Lang.tr("menu.route.terminate.lore") : Lang.tr("menu.leader-decides")),
                    leader ? p -> terminateMenu(p, r, self) : null);
        }
        m.set(18, Menu.icon(Material.ARROW, back()), back);
        m.open(player);
    }

    private void terminateMenu(Player player, TradeRoute r, Consumer<Player> back) {
        Menu m = new Menu(3, Component.text(Lang.tr("menu.terminate.title", r.shortId())));
        int slot = 10;
        for (TradeService.Reason reason : TradeService.Reason.values()) {
            String cmd = "trade terminate " + r.shortId() + " " + reason.name().toLowerCase(Locale.ROOT);
            m.set(slot, Menu.icon(Material.PAPER, reason.title(), Lang.tr("menu.next-confirm")),
                    p -> confirm(p, Lang.tr("menu.terminate.confirm", r.shortId()),
                            List.of(Lang.tr("menu.terminate.reason", reason.title())),
                            run(cmd), back));
            slot += 2;
        }
        m.set(18, Menu.icon(Material.ARROW, back()), back);
        m.open(player);
    }

    /** Route proposal wizard: period, then what we give, then what we take. */
    private void proposeTrade(Player player, Clan other) {
        prompt.ask(player, Lang.tr("menu.propose.days.ask", other.name()), Lang.tr("menu.propose.days.hint"),
                (p, daysText) -> {
                    String days = daysText.replaceAll("\\s+", "");
                    prompt.ask(p, Lang.tr("menu.propose.give.ask"),
                            Lang.tr("menu.propose.give.hint"),
                            (q, give) -> prompt.ask(q, Lang.tr("menu.propose.take.ask", other.name()),
                                    Lang.tr("menu.propose.take.hint"),
                                    (r, take) -> r.performCommand("trade propose " + other.name() + " " + days + " "
                                            + give.replaceAll("\\s+", "") + " " + take.replaceAll("\\s+", ""))));
                });
    }

    private void stableMenu(Player player, Consumer<Player> back) {
        Optional<Clan> ownOpt = clans.ofPlayer(player.getUniqueId());
        if (ownOpt.isEmpty()) {
            return;
        }
        Clan own = ownOpt.get();
        boolean trader = own.canTrade(player.getUniqueId());
        Consumer<Player> self = p -> stableMenu(p, back);
        Menu m = new Menu(4, Component.text(Lang.tr("menu.stable.title")));
        m.set(4, Menu.icon(Material.SADDLE, Lang.tr("menu.stable.add"), Lang.tr("menu.stable.add.lore"),
                trader ? Lang.tr("menu.stable.add.click") : Lang.tr("menu.leader-and-trusted")),
                trader ? run("trade stable add") : null);
        List<Stable.Entry> list = stable.of(own.id());
        int slot = 9;
        for (int i = 0; i < list.size() && slot < 27; i++) {
            Stable.Entry e = list.get(i);
            int n = i + 1;
            boolean wagon = e.kind() == Stable.Kind.WAGON;
            boolean home = e.status() == Stable.Status.HOME;
            m.set(slot++, Menu.icon(wagon ? Material.CHEST_MINECART : Material.LEATHER_HORSE_ARMOR,
                            Lang.tr("menu.stable.entry", n, wagon ? Lang.tr("menu.stable.wagon") : Lang.tr("menu.stable.horse")),
                            Lang.tr("menu.stable.speed", String.format(Locale.ROOT, "%.3f", e.speed())),
                            home ? Lang.tr("menu.stable.home") : Lang.tr("menu.stable.out"),
                            trader && home ? Lang.tr("menu.stable.click") : ""),
                    trader && home ? p -> stableCard(p, n, wagon, self) : null);
        }
        if (list.isEmpty()) {
            m.set(13, Menu.icon(Material.GRAY_DYE, Lang.tr("menu.stable.empty")), null);
        }
        m.set(27, Menu.icon(Material.ARROW, back()), back);
        m.open(player);
    }

    private void stableCard(Player player, int n, boolean wagon, Consumer<Player> back) {
        Menu m = new Menu(3, Component.text(Lang.tr("menu.stable.card.title", n)));
        m.set(11, Menu.icon(Material.LEAD, Lang.tr("menu.stable.take"), Lang.tr("menu.stable.take.lore1"),
                        Lang.tr("menu.stable.take.lore2")),
                run("trade stable take " + n));
        if (!wagon) {
            Optional<Clan> own = clans.ofPlayer(player.getUniqueId());
            String cost = config.get().trade().wagonCost().entrySet().stream()
                    .map(e -> e.getValue() + " " + e.getKey().name().toLowerCase(Locale.ROOT))
                    .reduce((a, b) -> a + ", " + b).orElse(Lang.tr("menu.stable.free"));
            m.set(15, Menu.icon(Material.CHEST, Lang.tr("menu.stable.upgrade"), Lang.tr("menu.stable.upgrade.lore"), cost),
                    own.isPresent() ? run("trade stable upgrade " + n) : null);
        }
        m.set(18, Menu.icon(Material.ARROW, back()), back);
        m.open(player);
    }

    // --- Plots ---

    private dev.hearthold.plot.PlotListener plotListener;

    public void setPlotListener(dev.hearthold.plot.PlotListener plotListener) {
        this.plotListener = plotListener;
    }

    public void plotMenu(Player player, Consumer<Player> back) {
        Optional<Clan> ownOpt = clans.ofPlayer(player.getUniqueId());
        Consumer<Player> self = p -> plotMenu(p, back);
        Menu m = new Menu(6, Component.text(Lang.tr("menu.plots.title")));
        m.set(0, Menu.icon(Material.NETHER_STAR, Lang.tr("menu.plots.top"), Lang.tr("menu.plots.top.lore")), run("plot top"));
        if (ownOpt.isEmpty()) {
            m.set(22, Menu.icon(Material.PAPER, Lang.tr("menu.noclan.title")), null);
            m.set(45, Menu.icon(Material.ARROW, back()), back);
            m.open(player);
            return;
        }
        Clan own = ownOpt.get();
        boolean leader = own.isLeader(player.getUniqueId());
        m.set(1, Menu.icon(Material.EXPERIENCE_BOTTLE, Lang.tr("menu.plots.prestige", plots.prestige(own.id()))), null);
        m.set(2, Menu.icon(Material.LECTERN, Lang.tr("menu.plots.heart"), Lang.tr("menu.plots.heart.lore1"),
                Lang.tr("menu.plots.heart.lore2"), Lang.tr("menu.plots.heart.lore3"),
                leader ? Lang.tr("menu.click-get") : Lang.tr("menu.leader-gets")), leader ? run("plot heart") : null);
        if (plotListener != null) {
            int[] size = plotListener.newPlotSize(player);
            m.set(3, Menu.icon(Material.SCAFFOLDING, Lang.tr("menu.plots.new-size", size[0], size[1], size[2]),
                    Lang.tr("menu.plots.new-size.lore1"), Lang.tr("menu.plots.new-size.lore2"),
                    Lang.tr("menu.plots.new-size.lore3"), Lang.tr("menu.plots.new-size.click")),
                    p -> newPlotSizeMenu(p, self));
        }

        // The plot the player is standing in.
        Optional<Plot> here = plots.at(player.getLocation().getBlock()).filter(p -> p.clanId().equals(own.id()));
        if (here.isPresent()) {
            Plot p = here.get();
            m.set(4, Menu.icon(Material.OAK_SIGN, Lang.tr("menu.plots.here", p.name()), p.size(),
                    Lang.tr("menu.invited", p.members().size()), Lang.tr("menu.click-plot-menu")), q -> plotCard(q, p, self));
        } else {
            m.set(4, Menu.icon(Material.GRAY_DYE, Lang.tr("menu.plots.not-here"),
                    Lang.tr("menu.plots.not-here.lore1"), Lang.tr("menu.plots.not-here.lore2")), null);
        }

        // The clan's plots.
        int slot = 18;
        for (Plot p : plots.of(own.id())) {
            if (slot >= 45) {
                break;
            }
            m.set(slot++, Menu.icon(Material.LECTERN, Lang.tr("menu.quoted", p.name()), p.size(),
                    Lang.tr("menu.invited", p.members().size()), Lang.tr("menu.plots.tier", plots.tier(p)),
                    Lang.tr("menu.click-plot-menu")),
                    q -> plotCard(q, p, self));
        }
        if (slot == 18) {
            m.set(22, Menu.icon(Material.PAPER, Lang.tr("menu.plots.none")), null);
        }
        m.set(45, Menu.icon(Material.ARROW, back()), back);
        m.open(player);
    }

    /** New plot size menu (stored per player). */
    private void newPlotSizeMenu(Player player, Consumer<Player> back) {
        int[] size = plotListener.newPlotSize(player);
        Menu m = new Menu(5, Component.text(Lang.tr("menu.plotsize.title")));
        m.set(4, Menu.icon(Material.SCAFFOLDING, Lang.tr("menu.plotsize.current", size[0], size[1], size[2]),
                Lang.tr("menu.plotsize.lore1"), Lang.tr("menu.plotsize.lore2"),
                Lang.tr("menu.plotsize.lore3"), Lang.tr("menu.plotsize.lore4", plots.maxSize())), null);
        axisRows(m, size, true, (p, next) -> {
            plotListener.setNewPlotSize(p, next);
            newPlotSizeMenu(p, back);
        });
        m.set(36, Menu.icon(Material.ARROW, back()), back);
        m.open(player);
    }

    /**
     * Rows 2-4: for X, Y, Z axes: "-5", "-1", value (click to enter a number), "+1", "+5".
     * apply receives the new size; the caller validates and stores it.
     */
    private void axisRows(Menu m, int[] size, boolean editable, java.util.function.BiConsumer<Player, int[]> apply) {
        String[] names = {Lang.tr("menu.axis.x"), Lang.tr("menu.axis.y"), Lang.tr("menu.axis.z")};
        String[] letters = {"X", "Y", "Z"};
        Material[] icons = {Material.RED_CONCRETE, Material.LIME_CONCRETE, Material.BLUE_CONCRETE};
        int[] deltas = {-5, -1, 1, 5};
        int[] offsets = {1, 2, 6, 7};
        for (int axis = 0; axis < 3; axis++) {
            int row = (axis + 1) * 9;
            int a = axis;
            m.set(row + 4, Menu.icon(icons[axis], names[axis] + ": " + size[axis],
                    editable ? Lang.tr("menu.click-enter-number") : Lang.tr("menu.axis.leader-changes")), !editable ? null
                    : p -> prompt.ask(p, Lang.tr("menu.axis.ask", names[a]), Lang.tr("menu.axis.hint", plots.maxSize()),
                            (r, text) -> {
                                try {
                                    int[] next = size.clone();
                                    next[a] = Integer.parseInt(text.trim());
                                    apply.accept(r, next);
                                } catch (NumberFormatException e) {
                                    r.sendMessage(Component.text(Lang.tr("menu.axis.need-integer"), NamedTextColor.RED));
                                }
                            }));
            if (!editable) {
                continue;
            }
            for (int i = 0; i < deltas.length; i++) {
                int d = deltas[i];
                int value = size[axis] + d;
                if (value < 1 || value > plots.maxSize()) {
                    continue;
                }
                m.set(row + offsets[i], Menu.icon(d < 0 ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE,
                        Lang.tr("menu.axis.step", (d > 0 ? "+" : "−") + Math.abs(d), letters[axis]),
                        Lang.tr("menu.axis.becomes", value)), p -> {
                    int[] next = size.clone();
                    next[a] = value;
                    apply.accept(p, next);
                });
            }
        }
    }

    /** Single plot screen: size per axis, name, members, rating, borders. */
    public void plotCard(Player player, Plot plot, Consumer<Player> back) {
        if (plots.byId(plot.id()).isEmpty()) {
            player.sendActionBar(Component.text(Lang.tr("menu.plot.gone"), NamedTextColor.RED));
            return;
        }
        Consumer<Player> self = p -> plotCard(p, plot, back);
        Optional<Clan> own = clans.ofPlayer(player.getUniqueId()).filter(c -> c.id().equals(plot.clanId()));
        if (own.isEmpty()) {
            return;
        }
        boolean leader = own.get().isLeader(player.getUniqueId());
        Menu m = new Menu(6, Component.text(Lang.tr("menu.plot.title", plot.name())));
        List<String> info = new ArrayList<>();
        info.add(Lang.tr("menu.plot.size", plot.size()));
        info.add(Lang.tr("menu.plot.from", plot.minX(), plot.minY(), plot.minZ()));
        info.add(Lang.tr("menu.plot.to", plot.maxX(), plot.maxY(), plot.maxZ()));
        info.add(Lang.tr("menu.invited", plot.members().size()));
        info.add(plot.ratedAt() > 0 ? Lang.tr("menu.plot.rating", String.format(Locale.ROOT, "%.1f", plot.score()),
                plots.tier(plot)) : Lang.tr("menu.plot.not-rated"));
        info.add(Lang.tr("menu.plot.chests1"));
        info.add(Lang.tr("menu.plot.chests2"));
        m.set(4, Menu.icon(Material.TARGET, Lang.tr("menu.quoted", plot.name()), info), null);
        int[] size = {plot.maxX() - plot.minX() + 1, plot.maxY() - plot.minY() + 1, plot.maxZ() - plot.minZ() + 1};
        axisRows(m, size, leader, (p, next) -> {
            try {
                plots.resize(plot, next[0], next[1], next[2]);
                p.sendActionBar(Component.text(Lang.tr("menu.plot.resized", plot.size()), NamedTextColor.GREEN));
                showPlot(p, plot, 5);
            } catch (dev.hearthold.clan.ClanException ex) {
                p.sendMessage(Component.text(ex.getMessage(), NamedTextColor.RED));
            }
            plotCard(p, plot, back);
        });
        m.set(46, Menu.icon(Material.COMPASS, Lang.tr("menu.plot.compass")), q -> {
            q.closeInventory();
            World w = Bukkit.getWorld(plot.world());
            if (w != null) {
                q.setCompassTarget(new Location(w, (plot.minX() + plot.maxX()) / 2.0 + 0.5, plot.minY(),
                        (plot.minZ() + plot.maxZ()) / 2.0 + 0.5));
                q.sendActionBar(Component.text(Lang.tr("menu.plot.compass.done", plot.name()), NamedTextColor.GOLD));
            }
        });
        m.set(47, Menu.icon(Material.GLOWSTONE_DUST, Lang.tr("menu.plot.borders"), Lang.tr("menu.plot.borders.lore")), q -> {
            q.closeInventory();
            showPlot(q, plot, 15);
        });
        m.set(48, Menu.icon(Material.GOLD_INGOT, Lang.tr("menu.plot.rate"), Lang.tr("menu.plot.rate.lore")), q -> {
            try {
                double score = plots.rate(plot);
                q.sendMessage(Component.text(Lang.tr("menu.plot.rate.result", plot.name(),
                        String.format(Locale.ROOT, "%.1f", score), plots.tier(plot), plots.prestige(plot.clanId())),
                        NamedTextColor.GREEN));
            } catch (dev.hearthold.clan.ClanException ex) {
                q.sendMessage(Component.text(ex.getMessage(), NamedTextColor.RED));
            }
            plotCard(q, plot, back);
        });
        if (leader) {
            m.set(50, Menu.icon(Material.NAME_TAG, Lang.tr("menu.plot.rename")), q -> prompt.ask(q,
                    Lang.tr("menu.plot.rename.ask"), null,
                    (r, name) -> {
                        try {
                            plots.rename(plot, name.trim());
                            r.sendMessage(Component.text(Lang.tr("menu.plot.rename.done", plot.name()), NamedTextColor.GREEN));
                        } catch (dev.hearthold.clan.ClanException ex) {
                            r.sendMessage(Component.text(ex.getMessage(), NamedTextColor.RED));
                        }
                    }));
            m.set(52, Menu.icon(Material.SCAFFOLDING, Lang.tr("menu.plot.set-size"), Lang.tr("menu.plot.set-size.lore")),
                    q -> prompt.ask(q, Lang.tr("menu.plot.set-size.ask"), Lang.tr("menu.plot.set-size.hint"),
                            (r, text) -> {
                                String[] parts = text.trim().split("[\\s,x×]+");
                                try {
                                    if (parts.length != 3) {
                                        throw new NumberFormatException();
                                    }
                                    plots.resize(plot, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                                            Integer.parseInt(parts[2]));
                                    r.sendMessage(Component.text(Lang.tr("menu.plot.resized", plot.size()), NamedTextColor.GREEN));
                                    showPlot(r, plot, 5);
                                } catch (NumberFormatException e) {
                                    r.sendMessage(Component.text(Lang.tr("menu.plot.set-size.need-three"), NamedTextColor.RED));
                                } catch (dev.hearthold.clan.ClanException ex) {
                                    r.sendMessage(Component.text(ex.getMessage(), NamedTextColor.RED));
                                }
                            }));
        }
        m.set(51, Menu.icon(Material.PLAYER_HEAD, Lang.tr("menu.plot.members"),
                leader ? Lang.tr("menu.plot.members.leader") : Lang.tr("menu.plot.members.lore")), q -> plotMembers(q, plot, self));
        m.set(45, Menu.icon(Material.ARROW, back()), back);
        m.open(player);
    }

    /** Particles along the 12 edges of the plot box, visible only to this player. */
    private static void showPlot(Player player, Plot plot, int seconds) {
        World w = Bukkit.getWorld(plot.world());
        org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager().getPlugin("Hearthold");
        if (w == null || plugin == null || !player.getWorld().equals(w)) {
            return;
        }
        double x0 = plot.minX(), y0 = plot.minY(), z0 = plot.minZ();
        double x1 = plot.maxX() + 1, y1 = plot.maxY() + 1, z1 = plot.maxZ() + 1;
        double[][] edges = {
                {x0, y0, z0, x1, y0, z0}, {x0, y1, z0, x1, y1, z0}, {x0, y0, z1, x1, y0, z1}, {x0, y1, z1, x1, y1, z1},
                {x0, y0, z0, x0, y1, z0}, {x1, y0, z0, x1, y1, z0}, {x0, y0, z1, x0, y1, z1}, {x1, y0, z1, x1, y1, z1},
                {x0, y0, z0, x0, y0, z1}, {x1, y0, z0, x1, y0, z1}, {x0, y1, z0, x0, y1, z1}, {x1, y1, z0, x1, y1, z1}};
        var dust = new org.bukkit.Particle.DustOptions(org.bukkit.Color.ORANGE, 1.2f);
        int[] runs = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (++runs[0] > seconds || !player.isOnline()) {
                task.cancel();
                return;
            }
            for (double[] e : edges) {
                double len = Math.max(Math.abs(e[3] - e[0]), Math.max(Math.abs(e[4] - e[1]), Math.abs(e[5] - e[2])));
                int steps = (int) Math.max(1, len * 2);
                for (int i = 0; i <= steps; i++) {
                    double t = i / (double) steps;
                    player.spawnParticle(org.bukkit.Particle.DUST, e[0] + (e[3] - e[0]) * t, e[1] + (e[4] - e[1]) * t,
                            e[2] + (e[5] - e[2]) * t, 1, dust);
                }
            }
        }, 0L, 20L);
    }

    private void plotMembers(Player player, Plot plot, Consumer<Player> back) {
        Consumer<Player> self = p -> plotMembers(p, plot, back);
        boolean leader = clans.byId(plot.clanId()).map(c -> c.isLeader(player.getUniqueId())).orElse(false);
        Menu m = new Menu(6, Component.text(Lang.tr("menu.plotmembers.title", plot.name())));
        int slot = 0;
        for (UUID id : plot.members()) {
            if (slot >= 18) {
                break;
            }
            String name = HeartMenu.nameOf(id);
            m.set(slot++, HeartMenu.head(id, name, leader
                            ? List.of(Lang.tr("menu.plotmembers.invited"), Lang.tr("menu.plotmembers.click-kick"))
                            : List.of(Lang.tr("menu.plotmembers.invited"))),
                    !leader ? null : p -> confirm(p, Lang.tr("menu.plotmembers.kick.confirm", name), List.of(), q -> {
                        plots.kick(plot, id);
                        q.sendMessage(Component.text(Lang.tr("menu.plotmembers.kicked", name, plot.name()), NamedTextColor.GREEN));
                        plotMembers(q, plot, back);
                    }, self));
        }
        if (leader) {
            m.set(18, Menu.icon(Material.WRITABLE_BOOK, Lang.tr("menu.plotmembers.invite"),
                    Lang.tr("menu.plotmembers.invite.lore")), null);
            slot = 27;
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (slot >= 45) {
                    break;
                }
                if (plot.members().contains(target.getUniqueId())) {
                    continue;
                }
                String name = target.getName();
                m.set(slot++, HeartMenu.head(target.getUniqueId(), name, List.of(Lang.tr("menu.plotmembers.click-invite"))), p -> {
                    plots.invite(plot, target.getUniqueId());
                    p.sendMessage(Component.text(Lang.tr("menu.plotmembers.invited-msg", name, plot.name()),
                            NamedTextColor.GREEN));
                    if (target.isOnline() && !target.equals(p)) {
                        target.sendMessage(Component.text(Lang.tr("menu.plotmembers.you-invited", plot.name()),
                                NamedTextColor.GREEN));
                    }
                    plotMembers(p, plot, back);
                });
            }
        }
        m.set(45, Menu.icon(Material.ARROW, back()), back);
        m.open(player);
    }

    // --- Chest access ---

    /** Access settings for a container on the base (leader Shift+right-click with an empty hand). */
    public void chestMenu(Player player, org.bukkit.block.Block block) {
        if (plotListener == null) {
            return;
        }
        ChestAccess chests = plotListener.chests();
        Optional<Plot> plot = plots.at(block);
        boolean inPlot = plot.isPresent();
        ChestAccess.Setting cur = chests.of(block, inPlot);
        Menu m = new Menu(4, Component.text(Lang.tr("menu.chest.title")));
        m.set(4, Menu.icon(Material.CHEST, Lang.tr("menu.chest.current", cur.describe())
                        + (chests.isCustom(block) ? "" : Lang.tr("menu.chest.default")),
                block.getX() + ", " + block.getY() + ", " + block.getZ(),
                inPlot ? Lang.tr("menu.chest.in-plot", plot.get().name()) : Lang.tr("menu.chest.on-base"),
                Lang.tr("menu.chest.lore1"), Lang.tr("menu.chest.lore2"),
                Lang.tr("menu.chest.lore3"), Lang.tr("menu.chest.lore4")), null);
        int slot = 10;
        for (ChestAccess.Mode mode : ChestAccess.Mode.values()) {
            if (mode == ChestAccess.Mode.PLOT && !inPlot) {
                continue;
            }
            boolean on = cur.mode() == mode;
            String hint = switch (mode) {
                case EVERYONE -> Lang.tr("menu.chest.mode.everyone");
                case CLAN -> Lang.tr("menu.chest.mode.clan");
                case PLOT -> Lang.tr("menu.chest.mode.plot");
                case TAGS -> Lang.tr("menu.chest.mode.tags");
            };
            m.set(slot, Menu.icon(on ? Material.LIME_DYE : Material.GRAY_DYE, mode.title(), hint,
                    on ? Lang.tr("menu.chest.selected") : Lang.tr("menu.chest.click-select")), p -> {
                chests.set(block, new ChestAccess.Setting(mode, mode == ChestAccess.Mode.TAGS ? cur.tags() : Set.of()));
                chestMenu(p, block);
            });
            slot += 2;
        }
        if (cur.mode() == ChestAccess.Mode.TAGS) {
            slot = 20;
            for (Clan.Tag tag : Clan.Tag.values()) {
                boolean on = cur.tags().contains(tag);
                m.set(slot, Menu.icon(on ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE,
                        Lang.tr(on ? "menu.chest.tag.on" : "menu.chest.tag.off", tag.title()),
                        tag.description(), on ? Lang.tr("menu.chest.tag.click-remove") : Lang.tr("menu.chest.tag.click-add")), p -> {
                    EnumSet<Clan.Tag> next = EnumSet.noneOf(Clan.Tag.class);
                    next.addAll(cur.tags());
                    if (on) {
                        next.remove(tag);
                    } else {
                        next.add(tag);
                    }
                    chests.set(block, new ChestAccess.Setting(ChestAccess.Mode.TAGS, next));
                    chestMenu(p, block);
                });
                slot += 2;
            }
        }
        m.set(27, Menu.icon(Material.CHEST, Lang.tr("menu.chest.open")), p -> {
            p.closeInventory();
            if (block.getState(false) instanceof org.bukkit.block.Container c) {
                p.openInventory(c.getInventory());
            }
        });
        m.open(player);
    }

    // --- Events (for everyone) ---

    public void eventsMenu(Player player, Consumer<Player> back) {
        Menu m = new Menu(6, Component.text(Lang.tr("menu.events.title")));
        int slot = 0;
        for (EventService.Site s : events.all()) {
            if (slot >= 45) {
                break;
            }
            boolean wave = s.type() == EventService.Type.WAVE;
            m.set(slot++, Menu.icon(wave ? Material.BLAZE_ROD : Material.CHEST, Lang.tr("menu.quoted", s.name()),
                            Lang.tr(wave ? "menu.events.waves" : "menu.events.chests", s.state().title()), s.coords(),
                            Lang.tr("menu.events.click-compass")),
                    run("event compass " + s.name()));
        }
        if (slot == 0) {
            m.set(22, Menu.icon(Material.PAPER, Lang.tr("menu.events.none")), null);
        }
        m.set(48, Menu.icon(Material.BOOK, Lang.tr("menu.events.list"), Lang.tr("menu.events.list.lore")), run("event"));
        if (back != null) {
            m.set(45, Menu.icon(Material.ARROW, back()), back);
        }
        m.open(player);
    }

    // --- Helpers ---

    public static void confirm(Player player, String title, List<String> lore, Consumer<Player> yes, Consumer<Player> no) {
        Menu m = new Menu(3, Component.text(title.length() > 32 ? title.substring(0, 31) + "…" : title));
        List<String> l = new ArrayList<>(lore);
        l.add(0, title);
        m.set(11, Menu.icon(Material.LIME_CONCRETE, Lang.tr("menu.confirm.yes"), l), yes);
        m.set(15, Menu.icon(Material.RED_CONCRETE, Lang.tr("menu.confirm.no")), no);
        m.open(player);
    }

    static Consumer<Player> run(String command) {
        return p -> {
            p.closeInventory();
            p.performCommand(command);
        };
    }
}
