package dev.hearthold.newbie;

import dev.hearthold.base.TerritoryService;
import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanService;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.i18n.Lang;
import dev.hearthold.storage.Database;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Newbie protection. A clan's first base, where no member has used protection yet or been in a clan whose
 * protection expired, is invulnerable for a few days. Protection is tied to accounts and granted once.
 * <p>
 * While protection lasts: smaller territory, no nodes, containers on the base limited.
 * Protection ends early if the clan accepts an experienced player, places a second base (proxy)
 * or its members break other clans' protected blocks.
 */
public final class NewbieService {

    private final Database db;
    private final ClanService clans;
    private final TerritoryService territory;
    private final Supplier<HeartholdConfig> config;
    private final Logger log;
    private final Map<UUID, Long> protectedUntil = new HashMap<>();
    private final Set<UUID> used = new HashSet<>();
    private final Set<UUID> experienced = new HashSet<>();

    public NewbieService(Database db, ClanService clans, TerritoryService territory, Supplier<HeartholdConfig> config,
                         Logger log) {
        this.db = db;
        this.clans = clans;
        this.territory = territory;
        this.config = config;
        this.log = log;
        clans.onDisband(clan -> protectedUntil.remove(clan.id()));
    }

    public void loadAll() {
        try (PreparedStatement ps = db.connection().prepareStatement("SELECT clan_id, until FROM newbie_protection");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                protectedUntil.put(UUID.fromString(rs.getString(1)), rs.getLong(2));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load newbie protection", e);
        }
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT player_id, newbie_used, experienced FROM accounts");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID id = UUID.fromString(rs.getString(1));
                if (rs.getInt(2) == 1) {
                    used.add(id);
                }
                if (rs.getInt(3) == 1) {
                    experienced.add(id);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load accounts", e);
        }
    }

    public boolean isProtected(UUID clanId) {
        Long until = protectedUntil.get(clanId);
        return until != null && until > System.currentTimeMillis();
    }

    public long until(UUID clanId) {
        return protectedUntil.getOrDefault(clanId, 0L);
    }

    public boolean isExperienced(UUID player) {
        return experienced.contains(player);
    }

    /** Whether the clan gets protection for its first heart (and the cheap heart price). */
    public boolean eligible(Clan clan) {
        if (territory.heartOf(clan.id()).isPresent() || protectedUntil.containsKey(clan.id())) {
            return false;
        }
        return clan.members().keySet().stream().noneMatch(p -> used.contains(p) || experienced.contains(p));
    }

    /** Territory radius limit while protected. */
    public int radiusLimit(UUID clanId) {
        return isProtected(clanId) ? config.get().newbie().radius() : Integer.MAX_VALUE;
    }

    /** A heart was placed: enable protection if the clan qualifies. */
    public void onHeartPlaced(Clan clan) {
        if (!eligibleIgnoringHeart(clan)) {
            return;
        }
        double days = config.get().newbie().days();
        if (days <= 0) {
            return;
        }
        long until = System.currentTimeMillis() + (long) (days * 86_400_000L);
        protectedUntil.put(clan.id(), until);
        save(clan.id(), until);
        clan.members().keySet().forEach(p -> mark(p, true, false));
        ClanService.broadcast(clan, Component.text(Lang.tr("misc.newbie.started",
                String.format(java.util.Locale.ROOT, "%.0f", days), config.get().newbie().radius(),
                config.get().newbie().maxContainers()), NamedTextColor.AQUA));
    }

    private boolean eligibleIgnoringHeart(Clan clan) {
        return !protectedUntil.containsKey(clan.id())
                && clan.members().keySet().stream().noneMatch(p -> used.contains(p) || experienced.contains(p));
    }

    /** Early or scheduled end of protection: all members become experienced. */
    public void end(Clan clan, String reason) {
        Long until = protectedUntil.get(clan.id());
        if (until == null || until <= 0) {
            return;
        }
        boolean wasActive = until > System.currentTimeMillis();
        protectedUntil.put(clan.id(), 0L);
        save(clan.id(), 0L);
        clan.members().keySet().forEach(p -> mark(p, false, true));
        if (wasActive || reason != null) {
            ClanService.broadcast(clan, Component.text(reason == null ? Lang.tr("misc.newbie.ended") : Lang.tr("misc.newbie.ended-reason", reason),
                    NamedTextColor.GOLD));
        }
    }

    /** A player joined a clan: in a clan without protection (with a base) they become experienced. */
    public void onJoin(Clan clan, UUID player) {
        if (isProtected(clan.id())) {
            if (experienced.contains(player)) {
                end(clan, Lang.tr("misc.newbie.reason.experienced-joined"));
            } else {
                mark(player, true, false);
            }
        } else if (territory.heartOf(clan.id()).isPresent()) {
            mark(player, false, true);
        }
    }

    /** Once per minute: expired protections. */
    public void tick() {
        long now = System.currentTimeMillis();
        for (var e : List.copyOf(protectedUntil.entrySet())) {
            if (e.getValue() > 0 && e.getValue() <= now) {
                clans.byId(e.getKey()).ifPresent(c -> end(c, Lang.tr("misc.newbie.reason.expired")));
            }
        }
    }

    /** For tests: the clan's protection ends in the given number of minutes (via the regular tick). */
    public boolean expireIn(UUID clanId, long minutes) {
        if (!isProtected(clanId)) {
            return false;
        }
        long until = System.currentTimeMillis() + minutes * 60_000L;
        protectedUntil.put(clanId, until);
        save(clanId, until);
        return true;
    }

    /** The player has already used newbie protection (or was in a clan whose protection expired). */
    public boolean hasUsed(UUID player) {
        return used.contains(player);
    }

    /** For tests: the player is a newbie again; their future clan gets protection for its first heart. */
    public void resetPlayer(UUID player) {
        used.remove(player);
        experienced.remove(player);
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT OR REPLACE INTO accounts (player_id, newbie_used, experienced) VALUES (?, 0, 0)")) {
            ps.setString(1, player.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to save account", e);
        }
    }

    private void mark(UUID player, boolean usedFlag, boolean experiencedFlag) {
        boolean changed = (usedFlag && used.add(player)) | (experiencedFlag && experienced.add(player));
        if (!changed) {
            return;
        }
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT OR REPLACE INTO accounts (player_id, newbie_used, experienced) VALUES (?, ?, ?)")) {
            ps.setString(1, player.toString());
            ps.setInt(2, used.contains(player) ? 1 : 0);
            ps.setInt(3, experienced.contains(player) ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to save account", e);
        }
    }

    private void save(UUID clanId, long until) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT OR REPLACE INTO newbie_protection (clan_id, until) VALUES (?, ?)")) {
            ps.setString(1, clanId.toString());
            ps.setLong(2, until);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to save newbie protection", e);
        }
    }
}
