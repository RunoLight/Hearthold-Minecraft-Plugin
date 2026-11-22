package dev.hearthold.base;

import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanException;
import dev.hearthold.clan.ClanService;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.economy.EconomyService;
import dev.hearthold.i18n.Lang;
import dev.hearthold.storage.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Base levels. The level sets the HP of protected blocks across the clan territory and the upkeep surcharge.
 * The leader upgrades it in the heart menu; the cost is taken from the heart treasury.
 */
public final class BaseLevelService {

    private final Database db;
    private final TerritoryService territory;
    private final EconomyService economy;
    private final Supplier<HeartholdConfig> config;
    private final Logger log;
    private final Map<UUID, Integer> levels = new HashMap<>();

    public BaseLevelService(Database db, ClanService clans, TerritoryService territory, EconomyService economy,
                            Supplier<HeartholdConfig> config, Logger log) {
        this.db = db;
        this.territory = territory;
        this.economy = economy;
        this.config = config;
        this.log = log;
        clans.onDisband(c -> levels.remove(c.id()));
    }

    public void loadAll() {
        try (PreparedStatement ps = db.connection().prepareStatement("SELECT clan_id, level FROM clan_levels");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                levels.put(UUID.fromString(rs.getString(1)), rs.getInt(2));
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to load base levels", e);
        }
    }

    /** The clan's base level, clamped to the levels currently in the config. */
    public HeartholdConfig.BaseLevel of(UUID clanId) {
        return config.get().baseLevel(levels.getOrDefault(clanId, 1));
    }

    /** HP of a protected block on the clan's territory. */
    public int maxHp(UUID clanId) {
        return of(clanId).hp();
    }

    /** Daily upkeep surcharge for the level. */
    public double upkeepPerDay(UUID clanId) {
        return territory.heartOf(clanId).isPresent() ? of(clanId).upkeepPerDay() : 0;
    }

    public Optional<HeartholdConfig.BaseLevel> next(UUID clanId) {
        int n = of(clanId).level();
        var all = config.get().baseLevels();
        return n < all.size() ? Optional.of(all.get(n)) : Optional.empty();
    }

    /** Upgrades the base level for emeralds from the heart treasury. */
    public HeartholdConfig.BaseLevel upgrade(Clan clan, UUID actor) throws ClanException {
        if (!clan.isLeader(actor)) {
            throw new ClanException(Lang.tr("base.level.leader-only"));
        }
        if (territory.heartOf(clan.id()).isEmpty()) {
            throw new ClanException(Lang.tr("base.place.heart-first"));
        }
        HeartholdConfig.BaseLevel next = next(clan.id())
                .orElseThrow(() -> new ClanException(Lang.tr("base.level.max")));
        double balance = economy.emeralds(clan.id());
        if (balance < next.upgradeCost()) {
            throw new ClanException(Lang.tr("base.level.not-enough",
                    String.format(Locale.ROOT, "%.0f", next.upgradeCost()), String.format(Locale.ROOT, "%.1f", balance)));
        }
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT OR REPLACE INTO clan_levels (clan_id, level) VALUES (?, ?)")) {
            ps.setString(1, clan.id().toString());
            ps.setInt(2, next.level());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.SEVERE, "Failed to save base level", e);
            throw new ClanException(Lang.tr("base.save-error"));
        }
        economy.adjust(clan, -next.upgradeCost());
        levels.put(clan.id(), next.level());
        return next;
    }

    /** Sets the level directly (for admins). */
    public void set(UUID clanId, int level) {
        int clamped = Math.max(1, Math.min(level, config.get().baseLevels().size()));
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT OR REPLACE INTO clan_levels (clan_id, level) VALUES (?, ?)")) {
            ps.setString(1, clanId.toString());
            ps.setInt(2, clamped);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to save base level", e);
            return;
        }
        levels.put(clanId, clamped);
    }
}
