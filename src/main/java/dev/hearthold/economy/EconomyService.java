package dev.hearthold.economy;

import dev.hearthold.base.Sphere;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Clan account in the base heart: upkeep emeralds and accumulated energy.
 * <p>
 * Once a minute every clan with a heart is charged a share of the daily cost (heart + nodes),
 * and an active, paid heart accumulates energy. When emeralds run out, the clan territory
 * stops protecting blocks until the account is topped up.
 */
public final class EconomyService {

    private static final class Account {
        double emeralds;
        double energy;
        boolean warnedLow;
    }

    private final Database db;
    private final ClanService clans;
    private final TerritoryService territory;
    private final Supplier<HeartholdConfig> config;
    private final Logger log;
    private final Map<UUID, Account> accounts = new HashMap<>();
    private long lastTickAt = System.currentTimeMillis();
    /** Upkeep cost multiplier (war weariness). */
    private java.util.function.ToDoubleFunction<UUID> costMultiplier = id -> 1.0;

    /** Bonus energy per hour (building prestige). */
    private java.util.function.ToDoubleFunction<UUID> energyBonus = id -> 0;

    public void setEnergyBonus(java.util.function.ToDoubleFunction<UUID> f) {
        this.energyBonus = f;
    }

    /** Extra daily upkeep (e.g. scouting for the web map). */
    private java.util.function.ToDoubleFunction<UUID> extraCost = id -> 0;

    public void setExtraCost(java.util.function.ToDoubleFunction<UUID> f) {
        this.extraCost = f;
    }

    public void setCostMultiplier(java.util.function.ToDoubleFunction<UUID> f) {
        this.costMultiplier = f;
    }

    public EconomyService(Database db, ClanService clans, TerritoryService territory,
                          Supplier<HeartholdConfig> config, Logger log) {
        this.db = db;
        this.clans = clans;
        this.territory = territory;
        this.config = config;
        this.log = log;
        clans.onDisband(clan -> accounts.remove(clan.id()));
        territory.setProtectionFilter(this::isProtected);
    }

    public void loadAll() {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT clan_id, emeralds, energy FROM clan_economy");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Account a = new Account();
                a.emeralds = rs.getDouble(2);
                a.energy = rs.getDouble(3);
                accounts.put(UUID.fromString(rs.getString(1)), a);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load clan economy", e);
        }
    }

    private Account account(UUID clanId) {
        return accounts.computeIfAbsent(clanId, id -> {
            Account a = new Account();
            a.emeralds = config.get().upkeep().startBalance();
            return a;
        });
    }

    public double emeralds(UUID clanId) {
        return account(clanId).emeralds;
    }

    public double energy(UUID clanId) {
        return account(clanId).energy;
    }

    /** Whether the clan territory protects blocks: upkeep is paid or disabled in the config. */
    public boolean isProtected(UUID clanId) {
        if (!config.get().upkeep().enabled()) {
            return true;
        }
        Account a = accounts.get(clanId);
        return a == null ? config.get().upkeep().startBalance() > 0 : a.emeralds > 0;
    }

    /** Daily upkeep cost: heart + each node. Zero without a heart. */
    public double dailyCost(UUID clanId) {
        HeartholdConfig.UpkeepSettings u = config.get().upkeep();
        if (territory.heartOf(clanId).isEmpty()) {
            return 0;
        }
        double proxies = config.get().newbie().proxyPerDay() * territory.proxyCount(clanId);
        return (u.heartPerDay() + u.nodePerDay() * territory.nodeCount(clanId) + proxies)
                * costMultiplier.applyAsDouble(clanId) + extraCost.applyAsDouble(clanId);
    }

    /** Energy per hour: base + members + nodes. Prestige and activities will be added here later. */
    public double energyPerHour(UUID clanId) {
        HeartholdConfig.EnergySettings e = config.get().energy();
        int members = clans.byId(clanId).map(c -> c.members().size()).orElse(0);
        return e.heartBasePerHour() + e.perMemberPerHour() * members + e.perNodePerHour() * territory.nodeCount(clanId)
                + energyBonus.applyAsDouble(clanId);
    }

    public void deposit(Clan clan, double amount) {
        Account a = account(clan.id());
        boolean wasProtected = isProtected(clan.id());
        a.emeralds += amount;
        a.warnedLow = false;
        save(clan.id(), a);
        if (!wasProtected && isProtected(clan.id())) {
            ClanService.broadcast(clan, Component.text(Lang.tr("economy.upkeep.restored"),
                    NamedTextColor.GREEN));
        }
    }

    /** Adds emeralds (may be negative), for admins. */
    public void adjust(Clan clan, double amount) {
        Account a = account(clan.id());
        a.emeralds = Math.max(0, a.emeralds + amount);
        save(clan.id(), a);
    }

    public boolean takeEnergy(UUID clanId, double amount) {
        Account a = account(clanId);
        if (a.energy < amount) {
            return false;
        }
        a.energy -= amount;
        save(clanId, a);
        return true;
    }

    /** Called once a minute. Uses real elapsed time so lag does not distort charges. */
    public void tick() {
        long now = System.currentTimeMillis();
        double hours = (now - lastTickAt) / 3_600_000.0;
        lastTickAt = now;
        HeartholdConfig c = config.get();

        for (Clan clan : clans.all()) {
            var heart = territory.heartOf(clan.id());
            if (heart.isEmpty()) {
                continue;
            }
            Account a = account(clan.id());
            double cost = dailyCost(clan.id());
            if (c.upkeep().enabled() && cost > 0) {
                boolean wasProtected = a.emeralds > 0;
                a.emeralds = Math.max(0, a.emeralds - cost * hours / 24.0);
                double daysLeft = a.emeralds / cost;
                if (wasProtected && a.emeralds <= 0) {
                    ClanService.broadcast(clan, Component.text(
                            Lang.tr("economy.upkeep.ran-out"), NamedTextColor.RED));
                } else if (a.emeralds > 0 && daysLeft < c.upkeep().warnDays() && !a.warnedLow) {
                    a.warnedLow = true;
                    ClanService.broadcast(clan, Component.text(Lang.tr("economy.upkeep.low",
                            String.format(Locale.ROOT, "%.1f", daysLeft * 24)), NamedTextColor.GOLD));
                }
            }
            Sphere h = heart.get();
            if (h.isActive(now) && isProtected(clan.id())) {
                a.energy = Math.min(c.energy().heartMax(), a.energy + energyPerHour(clan.id()) * hours);
            }
        }
        saveAll();
    }

    public void saveAll() {
        accounts.forEach(this::save);
    }

    private void save(UUID clanId, Account a) {
        if (clans.byId(clanId).isEmpty()) {
            return;
        }
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT OR REPLACE INTO clan_economy (clan_id, emeralds, energy) VALUES (?, ?, ?)")) {
            ps.setString(1, clanId.toString());
            ps.setDouble(2, a.emeralds);
            ps.setDouble(3, a.energy);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to save clan account " + clanId, e);
        }
    }
}
