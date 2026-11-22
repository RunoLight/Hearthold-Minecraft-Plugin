package dev.hearthold.war;

import dev.hearthold.i18n.Lang;
import dev.hearthold.base.TerritoryService;
import dev.hearthold.clan.AllyService;
import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanException;
import dev.hearthold.clan.ClanService;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.storage.Database;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wars, the war journal, war fatigue and shields.
 * <ul>
 *   <li>Sieging another base is possible only during an active war between the clans and if the base has no shield.</li>
 *   <li>Every war declaration adds fatigue to the attacker: pricier upkeep, slower HP regen on its bases.
 *       Fatigue decays by 1 per {@code decay-days} days.</li>
 *   <li>A war ends with a heart capture, peace (both sides), the attacker withdrawing, or by timeout.</li>
 * </ul>
 */
public final class WarService {

    private static final class State {
        double fatigue;
        long fatigueAt;
        long shieldUntil;
    }

    private final Database db;
    private final ClanService clans;
    private final AllyService allies;
    private final TerritoryService territory;
    private final Supplier<HeartholdConfig> config;
    private final Logger log;
    private final List<War> ongoing = new ArrayList<>();
    private final Map<UUID, State> states = new HashMap<>();
    /** Peace offers: proposer → target. */
    private final Map<UUID, Set<UUID>> peaceOffers = new HashMap<>();
    private VassalService vassals;
    /** Immune clans (newbie protection): war cannot be declared on them, their bases cannot be sieged. */
    private java.util.function.Predicate<UUID> immune = id -> false;
    /** Called when the attacker declares war (the attacker's newbie protection ends). */
    private java.util.function.Consumer<Clan> onDeclare = c -> { };

    public void setNewbieHooks(java.util.function.Predicate<UUID> immune, java.util.function.Consumer<Clan> onDeclare) {
        this.immune = immune;
        this.onDeclare = onDeclare;
    }

    public WarService(Database db, ClanService clans, AllyService allies, TerritoryService territory,
                      Supplier<HeartholdConfig> config, Logger log) {
        this.db = db;
        this.clans = clans;
        this.allies = allies;
        this.territory = territory;
        this.config = config;
        this.log = log;
        clans.onDisband(clan -> {
            states.remove(clan.id());
            for (War w : List.copyOf(ongoing)) {
                if (w.involves(clan.id())) {
                    end(w, War.Outcome.DISBANDED);
                }
            }
        });
    }

    public void setVassals(VassalService vassals) {
        this.vassals = vassals;
    }

    public void loadAll() {
        try (PreparedStatement ps = db.connection().prepareStatement("""
                SELECT id, attacker, defender, attacker_name, defender_name, declared_at, ends_at
                FROM wars WHERE ended_at IS NULL""");
             ResultSet rs = ps.executeQuery()) {
            long prepare = config.get().war().prepareMinutes() * 60_000L;
            while (rs.next()) {
                long declared = rs.getLong(6);
                ongoing.add(new War(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                        UUID.fromString(rs.getString(3)), rs.getString(4), rs.getString(5),
                        declared, declared + prepare, rs.getLong(7)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load wars", e);
        }
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT clan_id, fatigue, fatigue_at, shield_until FROM clan_war_state");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                State s = new State();
                s.fatigue = rs.getDouble(2);
                s.fatigueAt = rs.getLong(3);
                s.shieldUntil = rs.getLong(4);
                states.put(UUID.fromString(rs.getString(1)), s);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load fatigue and shields", e);
        }
    }

    // --- Queries ---

    public List<War> ongoing() {
        return List.copyOf(ongoing);
    }

    public Optional<War> warBetween(UUID a, UUID b) {
        return ongoing.stream().filter(w -> w.between(a, b)).findFirst();
    }

    public boolean atWar(UUID a, UUID b) {
        long now = System.currentTimeMillis();
        return ongoing.stream().anyMatch(w -> w.between(a, b) && w.isActive(now));
    }

    public double fatigue(UUID clanId) {
        State s = states.get(clanId);
        if (s == null) {
            return 0;
        }
        double days = (System.currentTimeMillis() - s.fatigueAt) / 86_400_000.0;
        return Math.max(0, s.fatigue - days / config.get().war().fatigueDecayDays());
    }

    /** Upkeep multiplier from fatigue. */
    public double upkeepMultiplier(UUID clanId) {
        return 1 + fatigue(clanId) * config.get().war().fatigueUpkeep();
    }

    /** HP regen speed multiplier on the clan's bases (below 1 when fatigued). */
    public double regenMultiplier(UUID clanId) {
        return 1 / (1 + fatigue(clanId) * config.get().war().fatigueRegen());
    }

    public long shieldUntil(UUID clanId) {
        State s = states.get(clanId);
        return s == null ? 0 : s.shieldUntil;
    }

    public boolean isShielded(UUID clanId) {
        return shieldUntil(clanId) > System.currentTimeMillis();
    }

    /**
     * Why a player of clan {@code attackerClan} cannot siege the base of {@code owner}; null if they can.
     */
    public String siegeDenial(UUID attackerClan, UUID owner) {
        if (immune.test(owner)) {
            return Lang.tr("war.siege.newbie");
        }
        if (!config.get().siegeRequiresWar()) {
            return isShielded(owner) ? Lang.tr("war.siege.shielded") : null;
        }
        if (attackerClan == null) {
            return Lang.tr("war.siege.clans-only");
        }
        if (isShielded(owner)) {
            return Lang.tr("war.siege.shielded");
        }
        if (!atWar(attackerClan, owner)) {
            Optional<War> w = warBetween(attackerClan, owner);
            return w.isPresent() ? Lang.tr("war.siege.preparing") : Lang.tr("war.siege.war-only");
        }
        return null;
    }

    // --- Declaration and end ---

    public War declare(Clan attacker, Clan defender) throws ClanException {
        if (attacker.id().equals(defender.id())) {
            throw new ClanException(Lang.tr("war.declare.self"));
        }
        if (allies.areAllies(attacker.id(), defender.id())) {
            throw new ClanException(Lang.tr("war.declare.allied", defender.name()));
        }
        if (warBetween(attacker.id(), defender.id()).isPresent()) {
            throw new ClanException(Lang.tr("war.declare.already"));
        }
        if (territory.heartOf(defender.id()).isEmpty()) {
            throw new ClanException(Lang.tr("war.declare.no-base"));
        }
        if (isShielded(defender.id())) {
            throw new ClanException(Lang.tr("war.declare.shielded", defender.name()));
        }
        if (immune.test(defender.id())) {
            throw new ClanException(Lang.tr("war.declare.newbie", defender.name()));
        }
        if (vassals != null && vassals.linked(attacker.id(), defender.id())) {
            throw new ClanException(Lang.tr("war.declare.vassal"));
        }
        HeartholdConfig.WarSettings ws = config.get().war();
        long now = System.currentTimeMillis();
        long activeAt = now + ws.prepareMinutes() * 60_000L;
        War w = new War(UUID.randomUUID(), attacker.id(), defender.id(), attacker.name(), defender.name(),
                now, activeAt, activeAt + (long) (ws.durationHours() * 3_600_000L));
        try (PreparedStatement ps = db.connection().prepareStatement("""
                INSERT INTO wars (id, attacker, defender, attacker_name, defender_name, declared_at, ends_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, w.id.toString());
            ps.setString(2, w.attacker.toString());
            ps.setString(3, w.defender.toString());
            ps.setString(4, w.attackerName);
            ps.setString(5, w.defenderName);
            ps.setLong(6, w.declaredAt);
            ps.setLong(7, w.endsAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ClanException(Lang.tr("war.error.save"));
        }
        ongoing.add(w);
        onDeclare.accept(attacker);
        State s = state(attacker.id());
        s.fatigue = fatigue(attacker.id()) + ws.fatiguePerWar();
        s.fatigueAt = now;
        // The attacker loses its own shield: you cannot attack while staying invulnerable.
        s.shieldUntil = 0;
        saveState(attacker.id(), s);

        String start = ws.prepareMinutes() > 0 ? " " + Lang.tr("war.declare.siege-starts", ws.prepareMinutes()) : "";
        Bukkit.broadcast(Component.text("⚔ " + Lang.tr("war.declare.broadcast", attacker.name(), defender.name())
                + start, NamedTextColor.RED));
        return w;
    }

    /**
     * For tests: a war without checks, shields or newbie protection, active right away (preparation skipped).
     * The declaration time is shifted back by the preparation time so the war is still active after a restart.
     */
    public War forceDeclare(Clan attacker, Clan defender) throws ClanException {
        if (attacker.id().equals(defender.id())) {
            throw new ClanException(Lang.tr("war.declare.self"));
        }
        if (warBetween(attacker.id(), defender.id()).isPresent()) {
            throw new ClanException(Lang.tr("war.declare.already"));
        }
        HeartholdConfig.WarSettings ws = config.get().war();
        long now = System.currentTimeMillis();
        long declared = now - ws.prepareMinutes() * 60_000L;
        War w = new War(UUID.randomUUID(), attacker.id(), defender.id(), attacker.name(), defender.name(),
                declared, now, now + (long) (ws.durationHours() * 3_600_000L));
        try (PreparedStatement ps = db.connection().prepareStatement("""
                INSERT INTO wars (id, attacker, defender, attacker_name, defender_name, declared_at, ends_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, w.id.toString());
            ps.setString(2, w.attacker.toString());
            ps.setString(3, w.defender.toString());
            ps.setString(4, w.attackerName);
            ps.setString(5, w.defenderName);
            ps.setLong(6, w.declaredAt);
            ps.setLong(7, w.endsAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ClanException(Lang.tr("war.error.save"));
        }
        ongoing.add(w);
        Bukkit.broadcast(Component.text("⚔ " + Lang.tr("war.declare.test-broadcast", attacker.name(), defender.name()),
                NamedTextColor.RED));
        return w;
    }

    /** For tests: clears the clan's fatigue and shield. */
    public void resetState(UUID clanId) {
        State s = state(clanId);
        s.fatigue = 0;
        s.fatigueAt = System.currentTimeMillis();
        s.shieldUntil = 0;
        saveState(clanId, s);
    }

    public void end(War w, War.Outcome outcome) {
        if (!w.isOngoing()) {
            return;
        }
        w.endedAt = System.currentTimeMillis();
        w.outcome = outcome;
        ongoing.remove(w);
        peaceOffers.getOrDefault(w.attacker, new HashSet<>()).remove(w.defender);
        peaceOffers.getOrDefault(w.defender, new HashSet<>()).remove(w.attacker);
        try (PreparedStatement ps = db.connection().prepareStatement(
                "UPDATE wars SET ended_at = ?, outcome = ? WHERE id = ?")) {
            ps.setLong(1, w.endedAt);
            ps.setString(2, outcome.name());
            ps.setString(3, w.id.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to end war", e);
        }
        Bukkit.broadcast(Component.text(Lang.tr("war.ended", w.attackerName, w.defenderName,
                outcome.title()), NamedTextColor.GOLD));
    }

    /** Peace offer. If the other side has already offered, peace is made (true). */
    public boolean offerPeace(Clan from, Clan to) throws ClanException {
        War w = warBetween(from.id(), to.id()).orElseThrow(() -> new ClanException(Lang.tr("war.no-war")));
        if (peaceOffers.getOrDefault(to.id(), Set.of()).contains(from.id())) {
            end(w, War.Outcome.PEACE);
            return true;
        }
        peaceOffers.computeIfAbsent(from.id(), k -> new HashSet<>()).add(to.id());
        return false;
    }

    /** The attacker withdraws unilaterally. */
    public void withdraw(Clan attacker, Clan defender) throws ClanException {
        War w = warBetween(attacker.id(), defender.id()).orElseThrow(() -> new ClanException(Lang.tr("war.no-war")));
        if (!w.attacker.equals(attacker.id())) {
            throw new ClanException(Lang.tr("war.withdraw.attacker-only"));
        }
        end(w, War.Outcome.WITHDRAWN);
    }

    public void shield(UUID clanId, double hours) {
        State s = state(clanId);
        s.shieldUntil = Math.max(s.shieldUntil, System.currentTimeMillis() + (long) (hours * 3_600_000L));
        saveState(clanId, s);
    }

    /** Once per second: ends wars that ran out of time. */
    public void tick() {
        long now = System.currentTimeMillis();
        for (War w : List.copyOf(ongoing)) {
            if (now >= w.endsAt) {
                end(w, War.Outcome.EXPIRED);
            }
        }
    }

    /** War journal: latest entries, newest first. */
    public List<War> journal(int limit) {
        List<War> result = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement("""
                SELECT id, attacker, defender, attacker_name, defender_name, declared_at, ends_at, ended_at, outcome
                FROM wars ORDER BY declared_at DESC LIMIT ?""")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    War w = new War(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                            UUID.fromString(rs.getString(3)), rs.getString(4), rs.getString(5),
                            rs.getLong(6), rs.getLong(6), rs.getLong(7));
                    w.endedAt = rs.getLong(8);
                    String outcome = rs.getString(9);
                    w.outcome = outcome == null ? null : War.Outcome.valueOf(outcome);
                    result.add(w);
                }
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to read war journal", e);
        }
        return result;
    }

    private State state(UUID clanId) {
        return states.computeIfAbsent(clanId, k -> {
            State s = new State();
            s.fatigueAt = System.currentTimeMillis();
            return s;
        });
    }

    private void saveState(UUID clanId, State s) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT OR REPLACE INTO clan_war_state (clan_id, fatigue, fatigue_at, shield_until) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, clanId.toString());
            ps.setDouble(2, s.fatigue);
            ps.setLong(3, s.fatigueAt);
            ps.setLong(4, s.shieldUntil);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to save clan war state", e);
        }
    }
}
