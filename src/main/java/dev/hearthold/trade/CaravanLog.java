package dev.hearthold.trade;

import dev.hearthold.storage.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Caravan and treasury log: who equipped, delivered or took what and when. Visible to leaders and admins. */
public final class CaravanLog {

    public record Entry(long at, String player, String action, String details) { }

    private final Database db;
    private final Logger log;

    public CaravanLog(Database db, Logger log) {
        this.db = db;
        this.log = log;
    }

    public void add(UUID clanId, String player, String action, String details) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT INTO caravan_log (clan_id, at, player, action, details) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, clanId.toString());
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, player);
            ps.setString(4, action);
            ps.setString(5, details);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to write the caravan log", e);
        }
    }

    /** Latest clan entries, newest first. */
    public List<Entry> recent(UUID clanId, int limit) {
        List<Entry> result = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT at, player, action, details FROM caravan_log WHERE clan_id = ? ORDER BY id DESC LIMIT ?")) {
            ps.setString(1, clanId.toString());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Entry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                }
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to read the caravan log", e);
        }
        return result;
    }
}
