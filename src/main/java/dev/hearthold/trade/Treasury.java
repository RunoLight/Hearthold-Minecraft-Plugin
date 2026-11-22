package dev.hearthold.trade;

import dev.hearthold.clan.ClanService;
import dev.hearthold.storage.Database;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Clan treasury: a virtual "material to amount" store. Accepts only plain items
 * (no names, enchantments or damage): the treasury stores the material and count, nothing else.
 */
public final class Treasury {

    private final Database db;
    private final Logger log;
    /** Table: clan_treasury for the clan treasury or tribute_treasury for the tribute treasury (key is the vassalage id). */
    private final String table;
    private final Map<UUID, Map<Material, Integer>> stock = new HashMap<>();

    public Treasury(Database db, ClanService clans, Logger log) {
        this(db, "clan_treasury", log);
        clans.onDisband(clan -> stock.remove(clan.id()));
    }

    public Treasury(Database db, String table, Logger log) {
        this.db = db;
        this.log = log;
        this.table = table;
    }

    /** Removes all contents of a key (e.g. a closed tribute treasury). */
    public Map<Material, Integer> drain(UUID key) {
        Map<Material, Integer> all = contents(key);
        all.forEach((m, a) -> take(key, m, a));
        return all;
    }

    public void loadAll() {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT clan_id, material, amount FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Material m = Material.matchMaterial(rs.getString(2));
                if (m != null) {
                    stock.computeIfAbsent(UUID.fromString(rs.getString(1)), k -> new HashMap<>()).put(m, rs.getInt(3));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load treasury", e);
        }
    }

    /** A plain item without metadata: the only kind the treasury accepts. */
    public static boolean accepts(ItemStack item) {
        return item != null && !item.isEmpty() && item.isSimilar(new ItemStack(item.getType()));
    }

    public int amount(UUID clanId, Material m) {
        return stock.getOrDefault(clanId, Map.of()).getOrDefault(m, 0);
    }

    /** Contents in alphabetical order. */
    public Map<Material, Integer> contents(UUID clanId) {
        return new TreeMap<>(stock.getOrDefault(clanId, Map.of()));
    }

    public void add(UUID clanId, Material m, int amount) {
        if (amount <= 0) {
            return;
        }
        Map<Material, Integer> s = stock.computeIfAbsent(clanId, k -> new HashMap<>());
        int now = s.merge(m, amount, Integer::sum);
        save(clanId, m, now);
    }

    /** Takes up to {@code amount} units; returns how many were actually taken. */
    public int take(UUID clanId, Material m, int amount) {
        Map<Material, Integer> s = stock.get(clanId);
        if (s == null || amount <= 0) {
            return 0;
        }
        int have = s.getOrDefault(m, 0);
        int taken = Math.min(have, amount);
        if (taken == 0) {
            return 0;
        }
        int left = have - taken;
        if (left == 0) {
            s.remove(m);
        } else {
            s.put(m, left);
        }
        save(clanId, m, left);
        return taken;
    }

    private void save(UUID clanId, Material m, int amount) {
        String sql = amount > 0
                ? "INSERT OR REPLACE INTO " + table + " (clan_id, material, amount) VALUES (?, ?, ?)"
                : "DELETE FROM " + table + " WHERE clan_id = ? AND material = ?";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, clanId.toString());
            ps.setString(2, m.name());
            if (amount > 0) {
                ps.setInt(3, amount);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to save clan treasury " + clanId, e);
        }
    }
}
