package dev.hearthold.clan;

import dev.hearthold.i18n.Lang;
import dev.hearthold.storage.Database;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Clans in memory, written to the DB on every change. */
public final class ClanService {

    // Cyrillic letters are allowed in clan names (validation pattern, not display text).
    private static final Pattern NAME = Pattern.compile("[A-Za-zА-Яа-яЁё0-9_]{3,16}");

    private final Database db;
    private final Logger log;
    private final Map<UUID, Clan> byId = new HashMap<>();
    private final Map<String, Clan> byName = new HashMap<>();
    private final Map<UUID, Clan> byPlayer = new HashMap<>();
    /** Invites: player → clan. Kept until server restart. */
    private final Map<UUID, UUID> invites = new HashMap<>();
    private final List<Consumer<Clan>> disbandListeners = new ArrayList<>();
    private final List<java.util.function.BiConsumer<Clan, UUID>> joinListeners = new ArrayList<>();

    /** Called when a player accepts an invite. */
    public void onJoin(java.util.function.BiConsumer<Clan, UUID> listener) {
        joinListeners.add(listener);
    }

    public ClanService(Database db, Logger log) {
        this.db = db;
        this.log = log;
    }

    public void loadAll() {
        Connection c = db.connection();
        try (PreparedStatement ps = c.prepareStatement("SELECT id, name, created_at FROM clans");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Clan clan = new Clan(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3));
                index(clan);
            }
        } catch (SQLException e) {
            log.log(Level.SEVERE, "Failed to load clans", e);
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT clan_id, player_id, role FROM clan_members");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Clan clan = byId.get(UUID.fromString(rs.getString(1)));
                if (clan == null) {
                    continue;
                }
                UUID player = UUID.fromString(rs.getString(2));
                clan.putMember(player, Clan.Role.valueOf(rs.getString(3)));
                byPlayer.put(player, clan);
            }
        } catch (SQLException e) {
            log.log(Level.SEVERE, "Failed to load clan members", e);
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT clan_id, player_id, tag FROM clan_tags");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Clan clan = byId.get(UUID.fromString(rs.getString(1)));
                UUID player = UUID.fromString(rs.getString(2));
                if (clan != null && clan.isMember(player)) {
                    clan.setTag(player, Clan.Tag.valueOf(rs.getString(3)), true);
                }
            }
        } catch (SQLException | IllegalArgumentException e) {
            log.log(Level.SEVERE, "Failed to load member tags", e);
        }
    }

    /** Sets or clears a member tag. Leader only. */
    public void setTag(Clan clan, UUID actor, UUID player, Clan.Tag tag, boolean on) throws ClanException {
        if (!clan.isLeader(actor)) {
            throw new ClanException(Lang.tr("clan.tag.leader-only"));
        }
        if (!clan.isMember(player)) {
            throw new ClanException(Lang.tr("clan.player.not-member"));
        }
        String sql = on ? "INSERT OR IGNORE INTO clan_tags (clan_id, player_id, tag) VALUES (?, ?, ?)"
                : "DELETE FROM clan_tags WHERE clan_id = ? AND player_id = ? AND tag = ?";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, clan.id().toString());
            ps.setString(2, player.toString());
            ps.setString(3, tag.name());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw storageError(e);
        }
        clan.setTag(player, tag, on);
    }

    /** The clan the player is invited to, if any (for the invites menu). */
    public Optional<UUID> invitedTo(UUID player) {
        return Optional.ofNullable(invites.get(player));
    }

    /** Called after a clan is disbanded (e.g. to remove its hearts from the world). */
    public void onDisband(Consumer<Clan> listener) {
        disbandListeners.add(listener);
    }

    /** Sends a message to every online clan member. */
    public static void broadcast(Clan clan, Component message) {
        for (UUID id : clan.members().keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendMessage(message);
            }
        }
    }

    public Collection<Clan> all() {
        return byId.values();
    }

    public Optional<Clan> byId(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<Clan> byName(String name) {
        return Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)));
    }

    public Optional<Clan> ofPlayer(UUID player) {
        return Optional.ofNullable(byPlayer.get(player));
    }

    public Clan create(String name, UUID leader) throws ClanException {
        if (!NAME.matcher(name).matches()) {
            throw new ClanException(Lang.tr("clan.name.invalid"));
        }
        if (byPlayer.containsKey(leader)) {
            throw new ClanException(Lang.tr("clan.already-in-clan"));
        }
        if (byName.containsKey(name.toLowerCase(Locale.ROOT))) {
            throw new ClanException(Lang.tr("clan.name.taken"));
        }
        Clan clan = new Clan(UUID.randomUUID(), name, System.currentTimeMillis());
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT INTO clans (id, name, created_at) VALUES (?, ?, ?)")) {
            ps.setString(1, clan.id().toString());
            ps.setString(2, clan.name());
            ps.setLong(3, clan.createdAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw storageError(e);
        }
        index(clan);
        addMember(clan, leader, Clan.Role.LEADER);
        return clan;
    }

    public void invite(Clan clan, UUID inviter, UUID target) throws ClanException {
        if (!clan.isLeader(inviter)) {
            throw new ClanException(Lang.tr("clan.invite.leader-only"));
        }
        if (byPlayer.containsKey(target)) {
            throw new ClanException(Lang.tr("clan.player.already-in-clan"));
        }
        invites.put(target, clan.id());
    }

    public Clan accept(UUID player) throws ClanException {
        UUID clanId = invites.remove(player);
        if (clanId == null) {
            throw new ClanException(Lang.tr("clan.invite.none"));
        }
        if (byPlayer.containsKey(player)) {
            throw new ClanException(Lang.tr("clan.already-in-clan"));
        }
        Clan clan = byId.get(clanId);
        if (clan == null) {
            throw new ClanException(Lang.tr("clan.gone"));
        }
        addMember(clan, player, Clan.Role.MEMBER);
        joinListeners.forEach(l -> l.accept(clan, player));
        return clan;
    }

    /** A clan with no members is kept (abandoned base) if this holds, e.g. it has a heart. */
    private java.util.function.Predicate<Clan> keepWhenEmpty = c -> false;
    private final List<Consumer<Clan>> abandonListeners = new ArrayList<>();

    public void setKeepWhenEmpty(java.util.function.Predicate<Clan> keep) {
        this.keepWhenEmpty = keep;
    }

    /** The clan has no members left but was not disbanded (its base is abandoned). */
    public void onAbandon(Consumer<Clan> listener) {
        abandonListeners.add(listener);
    }

    /**
     * Leaves the clan. The last member disbands it, unless the clan has a base: then the clan stays
     * memberless (abandoned base). The last leader hands over leadership.
     */
    public void leave(UUID player) throws ClanException {
        Clan clan = byPlayer.get(player);
        if (clan == null) {
            throw new ClanException(Lang.tr("clan.not-in-clan"));
        }
        boolean wasLeader = clan.isLeader(player);
        removeMember(clan, player);
        if (clan.members().isEmpty()) {
            if (keepWhenEmpty.test(clan)) {
                abandonListeners.forEach(l -> l.accept(clan));
            } else {
                disband(clan);
            }
            return;
        }
        boolean leaderLeft = clan.members().values().stream().noneMatch(r -> r == Clan.Role.LEADER);
        if (wasLeader && leaderLeft) {
            UUID heir = clan.members().keySet().iterator().next();
            setRole(clan, heir, Clan.Role.LEADER);
        }
    }

    /** Removes all members without disbanding (for tests: a dummy clan with an abandoned base). */
    public void abandon(Clan clan) throws ClanException {
        for (UUID member : List.copyOf(clan.members().keySet())) {
            removeMember(clan, member);
        }
        abandonListeners.forEach(l -> l.accept(clan));
    }

    /** A clanless player takes over an abandoned (memberless) clan and becomes its leader. */
    public void claimAbandoned(Clan clan, UUID player) throws ClanException {
        if (!clan.members().isEmpty()) {
            throw new ClanException(Lang.tr("clan.claim.has-members"));
        }
        if (byPlayer.containsKey(player)) {
            throw new ClanException(Lang.tr("clan.claim.leave-first"));
        }
        if (byId.get(clan.id()) == null) {
            throw new ClanException(Lang.tr("clan.gone"));
        }
        addMember(clan, player, Clan.Role.LEADER);
        invites.remove(player);
        joinListeners.forEach(l -> l.accept(clan, player));
    }

    private void addMember(Clan clan, UUID player, Clan.Role role) throws ClanException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT INTO clan_members (clan_id, player_id, role) VALUES (?, ?, ?)")) {
            ps.setString(1, clan.id().toString());
            ps.setString(2, player.toString());
            ps.setString(3, role.name());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw storageError(e);
        }
        clan.putMember(player, role);
        byPlayer.put(player, clan);
    }

    /** Grants or revokes trusted status. Cannot demote the leader this way. */
    public void setTrusted(Clan clan, UUID player, boolean trusted) throws ClanException {
        if (!clan.isMember(player)) {
            throw new ClanException(Lang.tr("clan.player.not-member"));
        }
        if (clan.isLeader(player)) {
            throw new ClanException(Lang.tr("clan.trusted.leader"));
        }
        setRole(clan, player, trusted ? Clan.Role.TRUSTED : Clan.Role.MEMBER);
    }

    private void setRole(Clan clan, UUID player, Clan.Role role) throws ClanException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "UPDATE clan_members SET role = ? WHERE player_id = ?")) {
            ps.setString(1, role.name());
            ps.setString(2, player.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw storageError(e);
        }
        clan.putMember(player, role);
    }

    private void removeMember(Clan clan, UUID player) throws ClanException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "DELETE FROM clan_members WHERE player_id = ?");
             PreparedStatement tags = db.connection().prepareStatement(
                "DELETE FROM clan_tags WHERE player_id = ?")) {
            ps.setString(1, player.toString());
            ps.executeUpdate();
            tags.setString(1, player.toString());
            tags.executeUpdate();
        } catch (SQLException e) {
            throw storageError(e);
        }
        clan.removeMember(player);
        byPlayer.remove(player);
    }

    /** Admin disband: kicks all members and disbands the clan (disband listeners clean up data). */
    public void forceDisband(Clan clan) throws ClanException {
        for (UUID member : List.copyOf(clan.members().keySet())) {
            removeMember(clan, member);
        }
        disband(clan);
    }

    private void disband(Clan clan) throws ClanException {
        try (PreparedStatement ps = db.connection().prepareStatement("DELETE FROM clans WHERE id = ?")) {
            ps.setString(1, clan.id().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw storageError(e);
        }
        byId.remove(clan.id());
        byName.remove(clan.name().toLowerCase(Locale.ROOT));
        invites.values().removeIf(id -> id.equals(clan.id()));
        disbandListeners.forEach(l -> l.accept(clan));
    }

    private void index(Clan clan) {
        byId.put(clan.id(), clan);
        byName.put(clan.name().toLowerCase(Locale.ROOT), clan);
    }

    private ClanException storageError(SQLException e) {
        log.log(Level.SEVERE, "Clan DB error", e);
        return new ClanException(Lang.tr("clan.error.save"));
    }
}
