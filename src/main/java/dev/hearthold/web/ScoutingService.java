package dev.hearthold.web;

import dev.hearthold.clan.ClanService;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.i18n.Lang;
import dev.hearthold.storage.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Scouting is a service a clan pays for through upkeep. The tier defines what the clan sees on the web map:
 * 0 — only bases and events; 1 — players outside bases with a long delay; 2 — couriers with cargo as well;
 * 3 — the same with a short delay.
 */
public final class ScoutingService {

    public static final int MAX_TIER = 3;
    /** An enabled tier stays on for at least this many hours, so it can't be turned on, used and turned off at once. */
    public static final long MIN_HOURS = 24;

    private final Database db;
    private final Supplier<HeartholdConfig> config;
    private final Logger log;
    private final Map<UUID, Integer> tiers = new HashMap<>();
    private final Map<UUID, Long> lockedUntil = new HashMap<>();

    public ScoutingService(Database db, ClanService clans, Supplier<HeartholdConfig> config, Logger log) {
        this.db = db;
        this.config = config;
        this.log = log;
        clans.onDisband(clan -> {
            tiers.remove(clan.id());
            lockedUntil.remove(clan.id());
        });
    }

    public void loadAll() {
        try (PreparedStatement ps = db.connection().prepareStatement("SELECT clan_id, tier, locked_until FROM clan_scouting");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                tiers.put(UUID.fromString(rs.getString(1)), rs.getInt(2));
                lockedUntil.put(UUID.fromString(rs.getString(1)), rs.getLong(3));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load scouting", e);
        }
    }

    public int tier(UUID clanId) {
        return tiers.getOrDefault(clanId, 0);
    }

    /** Daily upkeep surcharge for scouting. */
    public double dailyCost(UUID clanId) {
        return config.get().web().costOf(tier(clanId));
    }

    /** Player position delay for the tier; -1 means players are not visible. */
    public int delaySeconds(int tier) {
        var w = config.get().web();
        return switch (tier) {
            case 0 -> -1;
            case 1, 2 -> w.slowDelaySeconds();
            default -> w.fastDelaySeconds();
        };
    }

    /** Until when the tier cannot be lowered (0 means it can). */
    public long lockedUntil(UUID clanId) {
        return lockedUntil.getOrDefault(clanId, 0L);
    }

    /**
     * Changes the tier by the rules: raising is allowed any time and locks the tier for {@link #MIN_HOURS} hours;
     * lowering only after that. Returns an error message or null.
     */
    public String change(UUID clanId, int tier) {
        int t = Math.max(0, Math.min(MAX_TIER, tier));
        int current = tier(clanId);
        if (t == current) {
            return Lang.tr("web.scouting.alreadyOn");
        }
        long now = System.currentTimeMillis();
        if (t < current && now < lockedUntil(clanId)) {
            long min = (lockedUntil(clanId) - now + 59_999) / 60_000;
            String wait = min >= 60 ? Lang.tr("web.duration.hm", min / 60, min % 60) : Lang.tr("web.duration.m", min);
            return Lang.tr("web.scouting.locked", wait, MIN_HOURS);
        }
        long lock = t > current ? now + MIN_HOURS * 3_600_000L : lockedUntil(clanId);
        save(clanId, t, lock);
        return null;
    }

    /** Sets the tier without restrictions (for admins). */
    public void set(UUID clanId, int tier) {
        save(clanId, Math.max(0, Math.min(MAX_TIER, tier)), lockedUntil(clanId));
    }

    private void save(UUID clanId, int t, long lock) {
        tiers.put(clanId, t);
        lockedUntil.put(clanId, lock);
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT OR REPLACE INTO clan_scouting (clan_id, tier, locked_until) VALUES (?, ?, ?)")) {
            ps.setString(1, clanId.toString());
            ps.setInt(2, t);
            ps.setLong(3, lock);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to save scouting", e);
        }
    }

    public Map<UUID, Integer> snapshot() {
        return Map.copyOf(tiers);
    }
}
