package dev.hearthold.war;

import dev.hearthold.i18n.Lang;
import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanService;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.storage.Database;
import dev.hearthold.trade.Treasury;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Vassalages. A vassal pays tribute into a virtual tribute treasury: a share of its collectors' output and a share
 * of the cargo it equips on its trade routes. The suzerain collects tribute by caravan (/war tribute).
 * When the term ends, uncollected tribute returns to the vassal.
 */
public final class VassalService {

    public enum Kind { CONQUEST, PATRONAGE }

    public static final class Vassalage {
        final UUID id;
        final UUID suzerain;
        final UUID vassal;
        final String suzerainName;
        final String vassalName;
        final Kind kind;
        final long startedAt;
        final long endsAt;

        Vassalage(UUID id, UUID suzerain, UUID vassal, String suzerainName, String vassalName, Kind kind,
                  long startedAt, long endsAt) {
            this.id = id;
            this.suzerain = suzerain;
            this.vassal = vassal;
            this.suzerainName = suzerainName;
            this.vassalName = vassalName;
            this.kind = kind;
            this.startedAt = startedAt;
            this.endsAt = endsAt;
        }

        public UUID id() {
            return id;
        }

        public UUID suzerain() {
            return suzerain;
        }

        public UUID vassal() {
            return vassal;
        }

        public String suzerainName() {
            return suzerainName;
        }

        public String vassalName() {
            return vassalName;
        }

        public Kind kind() {
            return kind;
        }

        public long endsAt() {
            return endsAt;
        }
    }

    private final Database db;
    private final ClanService clans;
    private final Treasury treasury;
    private final Treasury tribute;
    private final Supplier<HeartholdConfig> config;
    private final Logger log;
    private final List<Vassalage> active = new ArrayList<>();
    /** Fractional tribute remainders until a whole item accumulates. */
    private final Map<UUID, Map<Material, Double>> fractions = new HashMap<>();

    public VassalService(Database db, ClanService clans, Treasury treasury, Treasury tribute,
                         Supplier<HeartholdConfig> config, Logger log) {
        this.db = db;
        this.clans = clans;
        this.treasury = treasury;
        this.tribute = tribute;
        this.config = config;
        this.log = log;
        clans.onDisband(clan -> {
            for (Vassalage v : List.copyOf(active)) {
                if (v.suzerain.equals(clan.id()) || v.vassal.equals(clan.id())) {
                    end(v, Lang.tr("war.vassalage.reason.disbanded"));
                }
            }
        });
    }

    public void loadAll() {
        try (PreparedStatement ps = db.connection().prepareStatement("""
                SELECT id, suzerain, vassal, suzerain_name, vassal_name, kind, started_at, ends_at
                FROM vassalages WHERE ended_at IS NULL""");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                active.add(new Vassalage(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                        UUID.fromString(rs.getString(3)), rs.getString(4), rs.getString(5),
                        Kind.valueOf(rs.getString(6)), rs.getLong(7), rs.getLong(8)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load vassalages", e);
        }
    }

    public List<Vassalage> active() {
        return List.copyOf(active);
    }

    public Optional<Vassalage> byId(UUID id) {
        return active.stream().filter(v -> v.id.equals(id)).findFirst();
    }

    public boolean isVassalage(UUID id) {
        return byId(id).isPresent();
    }

    public Optional<Vassalage> suzerainOf(UUID vassalClan) {
        return active.stream().filter(v -> v.vassal.equals(vassalClan)).findFirst();
    }

    public List<Vassalage> vassalsOf(UUID suzerainClan) {
        return active.stream().filter(v -> v.suzerain.equals(suzerainClan)).toList();
    }

    /** Whether the clans are linked by vassalage in either direction. */
    public boolean linked(UUID a, UUID b) {
        return active.stream().anyMatch(v -> (v.suzerain.equals(a) && v.vassal.equals(b))
                || (v.suzerain.equals(b) && v.vassal.equals(a)));
    }

    public Treasury tribute() {
        return tribute;
    }

    public Vassalage create(Clan suzerain, Clan vassal, Kind kind, double days) {
        // A new suzerain replaces the previous one.
        suzerainOf(vassal.id()).ifPresent(old -> end(old, Lang.tr("war.vassalage.reason.new-suzerain")));
        long now = System.currentTimeMillis();
        Vassalage v = new Vassalage(UUID.randomUUID(), suzerain.id(), vassal.id(), suzerain.name(), vassal.name(),
                kind, now, now + (long) (days * 86_400_000L));
        try (PreparedStatement ps = db.connection().prepareStatement("""
                INSERT INTO vassalages (id, suzerain, vassal, suzerain_name, vassal_name, kind, started_at, ends_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, v.id.toString());
            ps.setString(2, v.suzerain.toString());
            ps.setString(3, v.vassal.toString());
            ps.setString(4, v.suzerainName);
            ps.setString(5, v.vassalName);
            ps.setString(6, v.kind.name());
            ps.setLong(7, v.startedAt);
            ps.setLong(8, v.endsAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to save vassalage", e);
        }
        active.add(v);
        Bukkit.broadcast(Component.text(Lang.tr("war.vassalage.started", vassal.name(), suzerain.name(),
                kind == Kind.PATRONAGE ? " " + Lang.tr("war.vassals.patronage-suffix") : "",
                String.format(java.util.Locale.ROOT, "%.0f", days)), NamedTextColor.GOLD));
        return v;
    }

    public void end(Vassalage v, String why) {
        if (!active.remove(v)) {
            return;
        }
        try (PreparedStatement ps = db.connection().prepareStatement(
                "UPDATE vassalages SET ended_at = ? WHERE id = ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, v.id.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to end vassalage", e);
        }
        fractions.remove(v.id);
        // Uncollected tribute returns to the vassal.
        Map<Material, Integer> left = tribute.drain(v.id);
        if (clans.byId(v.vassal).isPresent()) {
            left.forEach((m, a) -> treasury.add(v.vassal, m, a));
        }
        Bukkit.broadcast(Component.text(Lang.tr("war.vassalage.ended", v.vassalName, v.suzerainName, why),
                NamedTextColor.GOLD));
    }

    /** Once per second: ends vassalages whose term is over. */
    public void tick() {
        long now = System.currentTimeMillis();
        for (Vassalage v : List.copyOf(active)) {
            if (now >= v.endsAt) {
                end(v, Lang.tr("war.vassalage.reason.expired"));
            }
        }
    }

    /** Collector tribute: returns how much of {@code amount} went to tribute. */
    public double divertCollector(UUID clanId, Material m, double amount) {
        Optional<Vassalage> v = suzerainOf(clanId);
        if (v.isEmpty()) {
            return 0;
        }
        double share = amount * config.get().war().tributeCollectors();
        addTribute(v.get(), m, share);
        return share;
    }

    /**
     * Trade route tribute: the vassal equipped {@code cargo}; a share of that cargo additionally moves from its
     * treasury to the tribute treasury (as much as is available).
     */
    public void divertTrade(UUID clanId, Map<Material, Integer> cargo) {
        Optional<Vassalage> v = suzerainOf(clanId);
        if (v.isEmpty()) {
            return;
        }
        double share = config.get().war().tributeTrade();
        cargo.forEach((m, a) -> {
            Map<Material, Double> f = fractions.computeIfAbsent(v.get().id, k -> new HashMap<>());
            double owed = f.getOrDefault(m, 0.0) + a * share;
            int whole = (int) Math.floor(owed + 1e-9);
            int taken = treasury.take(clanId, m, whole);
            tribute.add(v.get().id, m, taken);
            f.put(m, owed - whole);
        });
    }

    private void addTribute(Vassalage v, Material m, double amount) {
        Map<Material, Double> f = fractions.computeIfAbsent(v.id, k -> new HashMap<>());
        double total = f.getOrDefault(m, 0.0) + amount;
        int whole = (int) Math.floor(total + 1e-9);
        if (whole > 0) {
            tribute.add(v.id, m, whole);
        }
        f.put(m, total - whole);
    }
}
