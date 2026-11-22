package dev.hearthold.trade;

import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanException;
import dev.hearthold.clan.ClanService;
import dev.hearthold.i18n.Lang;
import dev.hearthold.storage.Database;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/** Trade routes: proposals, agreements, deliveries, forgiveness, termination, reputation, failure list. */
public final class TradeService {

    public static final int START_REPUTATION = 100;
    public static final int FAILURE_PENALTY = 10;

    /** Termination reasons. Only PARTNER_FAILED goes to the failure list and hurts reputation. */
    public enum Reason {
        PARTNER_FAILED("trade.reason.partner-failed"),
        NOT_NEEDED("trade.reason.not-needed"),
        WAR("trade.reason.war"),
        MUTUAL("trade.reason.mutual");

        private final String key;

        Reason(String key) {
            this.key = key;
        }

        public String title() {
            return Lang.tr(key);
        }
    }

    public record Proposal(int number, UUID from, UUID to, int periodDays, List<TradeRoute.Line> lines) { }

    public record Failure(String offender, String victim, String reason, long at) { }

    private final Database db;
    private final ClanService clans;
    private final Logger log;
    private final Map<UUID, TradeRoute> routes = new HashMap<>();
    private final Map<Integer, Proposal> proposals = new HashMap<>();
    private final Map<UUID, Integer> reputation = new HashMap<>();
    /** Last period whose overdue lines were already announced (to avoid spam). */
    private final Map<UUID, Integer> notifiedPeriod = new HashMap<>();
    private int nextProposal = 1;

    public TradeService(Database db, ClanService clans, Logger log) {
        this.db = db;
        this.clans = clans;
        this.log = log;
        clans.onDisband(clan -> {
            routes.values().removeIf(r -> r.involves(clan.id()));
            proposals.values().removeIf(p -> p.from().equals(clan.id()) || p.to().equals(clan.id()));
            reputation.remove(clan.id());
        });
    }

    public void loadAll() {
        Connection c = db.connection();
        try {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, clan_a, clan_b, period_days, start_at, status, terminated_by, reason, terminated_at FROM trade_routes");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TradeRoute r = new TradeRoute(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                            UUID.fromString(rs.getString(3)), rs.getInt(4), rs.getLong(5));
                    r.status = TradeRoute.Status.valueOf(rs.getString(6));
                    String by = rs.getString(7);
                    r.terminatedBy = by == null ? null : UUID.fromString(by);
                    r.reason = rs.getString(8);
                    r.terminatedAt = rs.getLong(9);
                    routes.put(r.id, r);
                    notifiedPeriod.put(r.id, r.currentPeriod());
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT route_id, from_clan, material, amount FROM trade_lines");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TradeRoute r = routes.get(UUID.fromString(rs.getString(1)));
                    Material m = Material.matchMaterial(rs.getString(3));
                    if (r != null && m != null) {
                        r.lines.add(new TradeRoute.Line(UUID.fromString(rs.getString(2)), m, rs.getInt(4)));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT route_id, period, from_clan, material, delivered FROM trade_deliveries");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TradeRoute r = routes.get(UUID.fromString(rs.getString(1)));
                    Material m = Material.matchMaterial(rs.getString(4));
                    if (r != null && m != null) {
                        r.delivered.put(new TradeRoute.DeliveryKey(rs.getInt(2), UUID.fromString(rs.getString(3)), m),
                                rs.getInt(5));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT route_id, period, from_clan FROM trade_forgiven");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TradeRoute r = routes.get(UUID.fromString(rs.getString(1)));
                    if (r != null) {
                        r.forgiven.add(new TradeRoute.ForgiveKey(rs.getInt(2), UUID.fromString(rs.getString(3))));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT clan_id, reputation FROM clan_reputation");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    reputation.put(UUID.fromString(rs.getString(1)), rs.getInt(2));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load trade routes", e);
        }
    }

    // --- Queries ---

    public Collection<TradeRoute> routesOf(UUID clanId) {
        return routes.values().stream().filter(r -> r.involves(clanId)).toList();
    }

    public Optional<TradeRoute> route(UUID id) {
        return Optional.ofNullable(routes.get(id));
    }

    /** A clan route by its short id (first characters of the id). */
    public TradeRoute find(UUID clanId, String shortId) throws ClanException {
        String key = shortId.startsWith("#") ? shortId.substring(1) : shortId;
        List<TradeRoute> found = routesOf(clanId).stream().filter(r -> r.id.toString().startsWith(key)).toList();
        if (found.size() != 1) {
            throw new ClanException(Lang.tr(found.isEmpty() ? "trade.route.not-found" : "trade.route.ambiguous"));
        }
        return found.getFirst();
    }

    public int reputation(UUID clanId) {
        return reputation.getOrDefault(clanId, START_REPUTATION);
    }

    public List<Proposal> proposalsTo(UUID clanId) {
        return proposals.values().stream().filter(p -> p.to().equals(clanId)).toList();
    }

    public List<Failure> failures(int limit) {
        List<Failure> result = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT offender_name, victim_name, reason, at FROM trade_failures ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Failure(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
                }
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to read the trade failure list", e);
        }
        return result;
    }

    // --- Proposal and agreement ---

    public Proposal propose(Clan from, Clan to, int periodDays, Map<Material, Integer> give, Map<Material, Integer> take)
            throws ClanException {
        if (from.id().equals(to.id())) {
            throw new ClanException(Lang.tr("trade.propose.self"));
        }
        if (periodDays < 1 || periodDays > 60) {
            throw new ClanException(Lang.tr("trade.propose.period-range"));
        }
        if (give.isEmpty() && take.isEmpty()) {
            throw new ClanException(Lang.tr("trade.propose.empty"));
        }
        List<TradeRoute.Line> lines = new ArrayList<>();
        give.forEach((m, a) -> lines.add(new TradeRoute.Line(from.id(), m, a)));
        take.forEach((m, a) -> lines.add(new TradeRoute.Line(to.id(), m, a)));
        Proposal p = new Proposal(nextProposal++, from.id(), to.id(), periodDays, List.copyOf(lines));
        proposals.put(p.number(), p);
        return p;
    }

    public TradeRoute accept(Clan clan, int number) throws ClanException {
        Proposal p = proposals.get(number);
        if (p == null || !p.to().equals(clan.id())) {
            throw new ClanException(Lang.tr("trade.proposal.not-found"));
        }
        if (clans.byId(p.from()).isEmpty()) {
            proposals.remove(number);
            throw new ClanException(Lang.tr("trade.proposal.clan-gone"));
        }
        proposals.remove(number);
        TradeRoute r = new TradeRoute(UUID.randomUUID(), p.from(), p.to(), p.periodDays(), System.currentTimeMillis());
        r.lines.addAll(p.lines());
        Connection c = db.connection();
        try {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO trade_routes (id, clan_a, clan_b, period_days, start_at, status) VALUES (?, ?, ?, ?, ?, ?)");
                 PreparedStatement pl = c.prepareStatement(
                         "INSERT INTO trade_lines (route_id, from_clan, material, amount) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, r.id.toString());
                ps.setString(2, r.clanA.toString());
                ps.setString(3, r.clanB.toString());
                ps.setInt(4, r.periodDays);
                ps.setLong(5, r.startAt);
                ps.setString(6, r.status.name());
                ps.executeUpdate();
                for (TradeRoute.Line l : r.lines) {
                    pl.setString(1, r.id.toString());
                    pl.setString(2, l.fromClan().toString());
                    pl.setString(3, l.material().name());
                    pl.setInt(4, l.amount());
                    pl.addBatch();
                }
                pl.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new ClanException(Lang.tr("trade.save-error"));
        }
        routes.put(r.id, r);
        notifiedPeriod.put(r.id, 0);
        return r;
    }

    public void decline(Clan clan, int number) throws ClanException {
        Proposal p = proposals.get(number);
        if (p == null || !p.to().equals(clan.id())) {
            throw new ClanException(Lang.tr("trade.proposal.not-found"));
        }
        proposals.remove(number);
    }

    // --- Delivery ---

    /**
     * Credits a delivery: fills the sender's earliest open lines first (overdue ones first).
     * Any surplus is credited to the current period.
     */
    public void deliver(TradeRoute r, UUID fromClan, Material m, int amount) {
        int current = r.currentPeriod();
        int left = amount;
        for (int p = 0; p <= current && left > 0; p++) {
            for (TradeRoute.Line l : r.lines) {
                if (!l.fromClan().equals(fromClan) || l.material() != m) {
                    continue;
                }
                TradeRoute.RowStatus s = r.rowStatus(p, l);
                if (s != TradeRoute.RowStatus.OVERDUE && s != TradeRoute.RowStatus.IN_PROGRESS) {
                    continue;
                }
                int need = l.amount() - r.delivered(p, fromClan, m);
                int put = Math.min(need, left);
                addDelivered(r, p, fromClan, m, put);
                left -= put;
            }
        }
        if (left > 0) {
            addDelivered(r, Math.max(0, current), fromClan, m, left);
        }
    }

    private void addDelivered(TradeRoute r, int period, UUID fromClan, Material m, int amount) {
        if (amount <= 0) {
            return;
        }
        int now = r.delivered.merge(new TradeRoute.DeliveryKey(period, fromClan, m), amount, Integer::sum);
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT OR REPLACE INTO trade_deliveries (route_id, period, from_clan, material, delivered) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, r.id.toString());
            ps.setInt(2, period);
            ps.setString(3, fromClan.toString());
            ps.setString(4, m.name());
            ps.setInt(5, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to save delivery", e);
        }
    }

    // --- Forgiveness and termination ---

    /** The wronged clan forgives the partner's overdue for a period (period numbers start at 1, as in the book). */
    public void forgive(Clan victim, TradeRoute r, int periodNumber) throws ClanException {
        UUID offender = r.partnerOf(victim.id());
        int period = periodNumber - 1;
        boolean overdue = r.lines.stream().anyMatch(l -> l.fromClan().equals(offender)
                && r.rowStatus(period, l) == TradeRoute.RowStatus.OVERDUE);
        if (!overdue) {
            throw new ClanException(Lang.tr("trade.forgive.no-overdue", periodNumber));
        }
        r.forgiven.add(new TradeRoute.ForgiveKey(period, offender));
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT OR IGNORE INTO trade_forgiven (route_id, period, from_clan) VALUES (?, ?, ?)")) {
            ps.setString(1, r.id.toString());
            ps.setInt(2, period);
            ps.setString(3, offender.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ClanException(Lang.tr("trade.save-error"));
        }
    }

    public void terminate(Clan by, TradeRoute r, Reason reason) throws ClanException {
        if (r.status != TradeRoute.Status.ACTIVE) {
            throw new ClanException(Lang.tr("trade.terminate.already"));
        }
        UUID partner = r.partnerOf(by.id());
        if (reason == Reason.PARTNER_FAILED && !r.hasOverdue(partner)) {
            throw new ClanException(Lang.tr("trade.terminate.no-overdue"));
        }
        r.status = TradeRoute.Status.TERMINATED;
        r.terminatedBy = by.id();
        r.reason = reason.name();
        r.terminatedAt = System.currentTimeMillis();
        try (PreparedStatement ps = db.connection().prepareStatement(
                "UPDATE trade_routes SET status = ?, terminated_by = ?, reason = ?, terminated_at = ? WHERE id = ?")) {
            ps.setString(1, r.status.name());
            ps.setString(2, by.id().toString());
            ps.setString(3, reason.name());
            ps.setLong(4, r.terminatedAt);
            ps.setString(5, r.id.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ClanException(Lang.tr("trade.save-error"));
        }
        if (reason == Reason.PARTNER_FAILED) {
            String partnerName = clans.byId(partner).map(Clan::name).orElse("?");
            try (PreparedStatement ps = db.connection().prepareStatement("""
                    INSERT INTO trade_failures (offender, offender_name, victim, victim_name, reason, at)
                    VALUES (?, ?, ?, ?, ?, ?)""")) {
                ps.setString(1, partner.toString());
                ps.setString(2, partnerName);
                ps.setString(3, by.id().toString());
                ps.setString(4, by.name());
                ps.setString(5, reason.title());
                ps.setLong(6, r.terminatedAt);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.log(Level.WARNING, "Failed to record trade failure", e);
            }
            changeReputation(partner, -FAILURE_PENALTY);
        }
    }

    private void changeReputation(UUID clanId, int delta) {
        int now = reputation(clanId) + delta;
        reputation.put(clanId, now);
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT OR REPLACE INTO clan_reputation (clan_id, reputation) VALUES (?, ?)")) {
            ps.setString(1, clanId.toString());
            ps.setInt(2, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to save reputation", e);
        }
    }

    // --- Overdue notifications ---

    /** Once a minute: when the period changes, tells both clans about overdue lines of the previous period. */
    public void tick() {
        for (TradeRoute r : routes.values()) {
            if (r.status != TradeRoute.Status.ACTIVE) {
                continue;
            }
            int current = r.currentPeriod();
            int last = notifiedPeriod.getOrDefault(r.id, current);
            if (current <= last) {
                continue;
            }
            notifiedPeriod.put(r.id, current);
            int ended = current - 1;
            for (UUID side : List.of(r.clanA, r.clanB)) {
                String overdue = r.lines.stream()
                        .filter(l -> l.fromClan().equals(side) && r.rowStatus(ended, l) == TradeRoute.RowStatus.OVERDUE)
                        .map(l -> (l.amount() - r.delivered(ended, side, l.material())) + " " + l.material().name())
                        .collect(Collectors.joining(", "));
                if (overdue.isEmpty()) {
                    continue;
                }
                String sideName = clans.byId(side).map(Clan::name).orElse("?");
                Component msg = Component.text(Lang.tr("trade.route.overdue", r.shortId(), sideName,
                        ended + 1, overdue), NamedTextColor.GOLD);
                clans.byId(r.clanA).ifPresent(c -> ClanService.broadcast(c, msg));
                clans.byId(r.clanB).ifPresent(c -> ClanService.broadcast(c, msg));
            }
        }
    }
}
