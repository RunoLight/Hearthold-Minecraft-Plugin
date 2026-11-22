package dev.hearthold.clan;

import dev.hearthold.i18n.Lang;
import dev.hearthold.storage.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Alliances between clans. An alliance is mutual: one leader proposes, the other clan's leader confirms.
 * Proposals live in memory until restart.
 */
public final class AllyService {

    private final Database db;
    private final Map<UUID, Set<UUID>> allies = new HashMap<>();
    /** Proposals: proposer → target. */
    private final Map<UUID, Set<UUID>> proposals = new HashMap<>();

    public AllyService(Database db, ClanService clans) {
        this.db = db;
        clans.onDisband(clan -> {
            Set<UUID> removed = allies.remove(clan.id());
            if (removed != null) {
                removed.forEach(other -> {
                    Set<UUID> set = allies.get(other);
                    if (set != null) {
                        set.remove(clan.id());
                    }
                });
            }
            proposals.remove(clan.id());
            proposals.values().forEach(s -> s.remove(clan.id()));
        });
    }

    public void loadAll() {
        try (PreparedStatement ps = db.connection().prepareStatement("SELECT clan_a, clan_b FROM clan_allies");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                link(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load alliances", e);
        }
    }

    public boolean areAllies(UUID a, UUID b) {
        return allies.getOrDefault(a, Set.of()).contains(b);
    }

    public Set<UUID> alliesOf(UUID clanId) {
        return Set.copyOf(allies.getOrDefault(clanId, Set.of()));
    }

    /**
     * Alliance proposal from {@code from} to clan {@code to}.
     * If {@code to} has already proposed, the alliance is formed and the method returns true.
     */
    public boolean propose(UUID from, UUID to) throws ClanException {
        if (from.equals(to)) {
            throw new ClanException(Lang.tr("clan.ally.self"));
        }
        if (areAllies(from, to)) {
            throw new ClanException(Lang.tr("clan.ally.already"));
        }
        if (proposals.getOrDefault(to, Set.of()).contains(from)) {
            proposals.get(to).remove(from);
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "INSERT INTO clan_allies (clan_a, clan_b, since) VALUES (?, ?, ?)")) {
                ps.setString(1, from.toString());
                ps.setString(2, to.toString());
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new ClanException(Lang.tr("clan.error.save"));
            }
            link(from, to);
            return true;
        }
        proposals.computeIfAbsent(from, k -> new HashSet<>()).add(to);
        return false;
    }

    public void breakAlliance(UUID a, UUID b) throws ClanException {
        if (!areAllies(a, b)) {
            throw new ClanException(Lang.tr("clan.ally.not-allied"));
        }
        try (PreparedStatement ps = db.connection().prepareStatement(
                "DELETE FROM clan_allies WHERE (clan_a = ? AND clan_b = ?) OR (clan_a = ? AND clan_b = ?)")) {
            ps.setString(1, a.toString());
            ps.setString(2, b.toString());
            ps.setString(3, b.toString());
            ps.setString(4, a.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ClanException(Lang.tr("clan.error.save"));
        }
        allies.getOrDefault(a, new HashSet<>()).remove(b);
        allies.getOrDefault(b, new HashSet<>()).remove(a);
    }

    private void link(UUID a, UUID b) {
        allies.computeIfAbsent(a, k -> new HashSet<>()).add(b);
        allies.computeIfAbsent(b, k -> new HashSet<>()).add(a);
    }
}
