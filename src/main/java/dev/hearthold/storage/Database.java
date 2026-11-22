package dev.hearthold.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * A single SQLite connection. All calls come from the main server thread,
 * so no pool is needed. Later replaced with PostgreSQL for the web portal.
 */
public final class Database {

    /**
     * Schema migrations in order. The number of the applied migration is stored in PRAGMA user_version.
     * Released migrations never change: new ones are only appended.
     */
    private static final List<List<String>> MIGRATIONS = List.of(
            // 1: clans and hearts
            List.of("""
                    CREATE TABLE IF NOT EXISTS clans (
                        id          TEXT PRIMARY KEY,
                        name        TEXT NOT NULL UNIQUE COLLATE NOCASE,
                        created_at  INTEGER NOT NULL
                    )""", """
                    CREATE TABLE IF NOT EXISTS clan_members (
                        clan_id     TEXT NOT NULL REFERENCES clans(id) ON DELETE CASCADE,
                        player_id   TEXT NOT NULL PRIMARY KEY,
                        role        TEXT NOT NULL
                    )""", """
                    CREATE TABLE IF NOT EXISTS hearts (
                        id          TEXT PRIMARY KEY,
                        clan_id     TEXT NOT NULL REFERENCES clans(id) ON DELETE CASCADE,
                        world       TEXT NOT NULL,
                        x           INTEGER NOT NULL,
                        y           INTEGER NOT NULL,
                        z           INTEGER NOT NULL,
                        radius      INTEGER NOT NULL,
                        placed_at   INTEGER NOT NULL
                    )"""),
            // 2: hearts and nodes in one spheres table, activation delay, allies, block HP
            List.of(
                    "ALTER TABLE hearts RENAME TO spheres",
                    "ALTER TABLE spheres ADD COLUMN kind TEXT NOT NULL DEFAULT 'HEART'",
                    "ALTER TABLE spheres ADD COLUMN active_at INTEGER NOT NULL DEFAULT 0",
                    """
                    CREATE TABLE clan_allies (
                        clan_a      TEXT NOT NULL REFERENCES clans(id) ON DELETE CASCADE,
                        clan_b      TEXT NOT NULL REFERENCES clans(id) ON DELETE CASCADE,
                        since       INTEGER NOT NULL,
                        PRIMARY KEY (clan_a, clan_b)
                    )""", """
                    CREATE TABLE block_health (
                        world       TEXT NOT NULL,
                        x           INTEGER NOT NULL,
                        y           INTEGER NOT NULL,
                        z           INTEGER NOT NULL,
                        hp          REAL NOT NULL,
                        max_hp      INTEGER NOT NULL,
                        last_hit_at INTEGER NOT NULL,
                        saved_at    INTEGER NOT NULL,
                        PRIMARY KEY (world, x, y, z)
                    )"""),
            // 3: economy: clan balance in the heart, chunk energy, buyer stock
            List.of("""
                    CREATE TABLE clan_economy (
                        clan_id     TEXT PRIMARY KEY REFERENCES clans(id) ON DELETE CASCADE,
                        emeralds    REAL NOT NULL,
                        energy      REAL NOT NULL
                    )""", """
                    CREATE TABLE chunk_energy (
                        world       TEXT NOT NULL,
                        cx          INTEGER NOT NULL,
                        cz          INTEGER NOT NULL,
                        energy      REAL NOT NULL,
                        updated_at  INTEGER NOT NULL,
                        PRIMARY KEY (world, cx, cz)
                    )""", """
                    CREATE TABLE buyer_credit (
                        player_id   TEXT PRIMARY KEY,
                        amount      REAL NOT NULL
                    )"""),
            // 4: resource collectors and their accumulated output (not yet put into the barrel)
            List.of("""
                    CREATE TABLE collectors (
                        id          TEXT PRIMARY KEY,
                        clan_id     TEXT NOT NULL REFERENCES clans(id) ON DELETE CASCADE,
                        world       TEXT NOT NULL,
                        x           INTEGER NOT NULL,
                        y           INTEGER NOT NULL,
                        z           INTEGER NOT NULL,
                        placed_at   INTEGER NOT NULL
                    )""", """
                    CREATE TABLE collector_buffer (
                        collector_id TEXT NOT NULL REFERENCES collectors(id) ON DELETE CASCADE,
                        material     TEXT NOT NULL,
                        amount       REAL NOT NULL,
                        PRIMARY KEY (collector_id, material)
                    )"""),
            // 5: treasury, trade routes, reputation, caravan log, stable
            List.of("""
                    CREATE TABLE clan_treasury (
                        clan_id     TEXT NOT NULL REFERENCES clans(id) ON DELETE CASCADE,
                        material    TEXT NOT NULL,
                        amount      INTEGER NOT NULL,
                        PRIMARY KEY (clan_id, material)
                    )""", """
                    CREATE TABLE trade_routes (
                        id            TEXT PRIMARY KEY,
                        clan_a        TEXT NOT NULL REFERENCES clans(id) ON DELETE CASCADE,
                        clan_b        TEXT NOT NULL REFERENCES clans(id) ON DELETE CASCADE,
                        period_days   INTEGER NOT NULL,
                        start_at      INTEGER NOT NULL,
                        status        TEXT NOT NULL,
                        terminated_by TEXT,
                        reason        TEXT,
                        terminated_at INTEGER
                    )""", """
                    CREATE TABLE trade_lines (
                        route_id    TEXT NOT NULL REFERENCES trade_routes(id) ON DELETE CASCADE,
                        from_clan   TEXT NOT NULL,
                        material    TEXT NOT NULL,
                        amount      INTEGER NOT NULL
                    )""", """
                    CREATE TABLE trade_deliveries (
                        route_id    TEXT NOT NULL REFERENCES trade_routes(id) ON DELETE CASCADE,
                        period      INTEGER NOT NULL,
                        from_clan   TEXT NOT NULL,
                        material    TEXT NOT NULL,
                        delivered   INTEGER NOT NULL,
                        PRIMARY KEY (route_id, period, from_clan, material)
                    )""", """
                    CREATE TABLE trade_forgiven (
                        route_id    TEXT NOT NULL REFERENCES trade_routes(id) ON DELETE CASCADE,
                        period      INTEGER NOT NULL,
                        from_clan   TEXT NOT NULL,
                        PRIMARY KEY (route_id, period, from_clan)
                    )""", """
                    CREATE TABLE trade_failures (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        offender      TEXT NOT NULL,
                        offender_name TEXT NOT NULL,
                        victim        TEXT NOT NULL,
                        victim_name   TEXT NOT NULL,
                        reason        TEXT NOT NULL,
                        at            INTEGER NOT NULL
                    )""", """
                    CREATE TABLE clan_reputation (
                        clan_id     TEXT PRIMARY KEY REFERENCES clans(id) ON DELETE CASCADE,
                        reputation  INTEGER NOT NULL
                    )""", """
                    CREATE TABLE caravan_log (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        clan_id     TEXT NOT NULL,
                        at          INTEGER NOT NULL,
                        player      TEXT NOT NULL,
                        action      TEXT NOT NULL,
                        details     TEXT NOT NULL
                    )""", """
                    CREATE TABLE stable (
                        id          TEXT PRIMARY KEY,
                        clan_id     TEXT NOT NULL REFERENCES clans(id) ON DELETE CASCADE,
                        kind        TEXT NOT NULL,
                        data        BLOB,
                        speed       REAL NOT NULL,
                        status      TEXT NOT NULL,
                        entity_id   TEXT
                    )"""),
            // 6: wars, war fatigue and shields, vassalage and tribute treasury
            List.of("""
                    CREATE TABLE wars (
                        id            TEXT PRIMARY KEY,
                        attacker      TEXT NOT NULL,
                        defender      TEXT NOT NULL,
                        attacker_name TEXT NOT NULL,
                        defender_name TEXT NOT NULL,
                        declared_at   INTEGER NOT NULL,
                        ends_at       INTEGER NOT NULL,
                        ended_at      INTEGER,
                        outcome       TEXT
                    )""", """
                    CREATE TABLE clan_war_state (
                        clan_id       TEXT PRIMARY KEY REFERENCES clans(id) ON DELETE CASCADE,
                        fatigue       REAL NOT NULL,
                        fatigue_at    INTEGER NOT NULL,
                        shield_until  INTEGER NOT NULL
                    )""", """
                    CREATE TABLE vassalages (
                        id            TEXT PRIMARY KEY,
                        suzerain      TEXT NOT NULL,
                        vassal        TEXT NOT NULL,
                        suzerain_name TEXT NOT NULL,
                        vassal_name   TEXT NOT NULL,
                        kind          TEXT NOT NULL,
                        started_at    INTEGER NOT NULL,
                        ends_at       INTEGER NOT NULL,
                        ended_at      INTEGER
                    )""", """
                    CREATE TABLE tribute_treasury (
                        clan_id     TEXT NOT NULL,
                        material    TEXT NOT NULL,
                        amount      INTEGER NOT NULL,
                        PRIMARY KEY (clan_id, material)
                    )"""),
            // 7: clan crafting stations and timed jobs (enchanting, anvil)
            List.of("""
                    CREATE TABLE stations (
                        world       TEXT NOT NULL,
                        x           INTEGER NOT NULL,
                        y           INTEGER NOT NULL,
                        z           INTEGER NOT NULL,
                        clan_id     TEXT NOT NULL REFERENCES clans(id) ON DELETE CASCADE,
                        type        TEXT NOT NULL,
                        PRIMARY KEY (world, x, y, z)
                    )""", """
                    CREATE TABLE craft_jobs (
                        id          TEXT PRIMARY KEY,
                        world       TEXT NOT NULL,
                        x           INTEGER NOT NULL,
                        y           INTEGER NOT NULL,
                        z           INTEGER NOT NULL,
                        station     TEXT NOT NULL,
                        item        BLOB NOT NULL,
                        clan_id     TEXT,
                        player      TEXT NOT NULL,
                        started_at  INTEGER NOT NULL,
                        finish_at   INTEGER NOT NULL
                    )"""),
            // 8: plots inside bases, invited players, building appraisal
            List.of("""
                    CREATE TABLE plots (
                        id          TEXT PRIMARY KEY,
                        clan_id     TEXT NOT NULL REFERENCES clans(id) ON DELETE CASCADE,
                        name        TEXT NOT NULL,
                        world       TEXT NOT NULL,
                        x           INTEGER NOT NULL,
                        y           INTEGER NOT NULL,
                        z           INTEGER NOT NULL,
                        sx          INTEGER NOT NULL,
                        sy          INTEGER NOT NULL,
                        sz          INTEGER NOT NULL,
                        score       REAL NOT NULL,
                        rated_at    INTEGER NOT NULL,
                        created_at  INTEGER NOT NULL
                    )""", """
                    CREATE TABLE plot_members (
                        plot_id     TEXT NOT NULL REFERENCES plots(id) ON DELETE CASCADE,
                        player_id   TEXT NOT NULL,
                        PRIMARY KEY (plot_id, player_id)
                    )"""),
            // 9: newbie protection: clan protection expiry and account flags
            List.of("""
                    CREATE TABLE newbie_protection (
                        clan_id     TEXT PRIMARY KEY REFERENCES clans(id) ON DELETE CASCADE,
                        until       INTEGER NOT NULL
                    )""", """
                    CREATE TABLE accounts (
                        player_id    TEXT PRIMARY KEY,
                        newbie_used  INTEGER NOT NULL DEFAULT 0,
                        experienced  INTEGER NOT NULL DEFAULT 0
                    )"""),
            // 10: bodies of offline players
            List.of("""
                    CREATE TABLE bodies (
                        player_id   TEXT PRIMARY KEY,
                        player_name TEXT NOT NULL,
                        entity_id   TEXT NOT NULL,
                        world       TEXT NOT NULL,
                        x           REAL NOT NULL,
                        y           REAL NOT NULL,
                        z           REAL NOT NULL,
                        items       BLOB NOT NULL,
                        killed      INTEGER NOT NULL DEFAULT 0,
                        killer      TEXT,
                        created_at  INTEGER NOT NULL
                    )"""),
            // 11: event sites (Nether waves, timed chests)
            List.of("""
                    CREATE TABLE event_sites (
                        name        TEXT PRIMARY KEY,
                        type        TEXT NOT NULL,
                        world       TEXT NOT NULL,
                        x           INTEGER NOT NULL,
                        y           INTEGER NOT NULL,
                        z           INTEGER NOT NULL,
                        radius      INTEGER NOT NULL,
                        state       TEXT NOT NULL,
                        next_at     INTEGER NOT NULL
                    )"""),
            // 12: clan scouting level for the web map
            List.of("""
                    CREATE TABLE clan_scouting (
                        clan_id     TEXT PRIMARY KEY REFERENCES clans(id) ON DELETE CASCADE,
                        tier        INTEGER NOT NULL
                    )"""),
            // 13: clan member tags (treasurer, quartermaster)
            List.of("""
                    CREATE TABLE clan_tags (
                        clan_id     TEXT NOT NULL REFERENCES clans(id) ON DELETE CASCADE,
                        player_id   TEXT NOT NULL,
                        tag         TEXT NOT NULL,
                        PRIMARY KEY (player_id, tag)
                    )"""),
            // 14: resources assigned to a chunk by an admin (instead of a region)
            List.of("""
                    CREATE TABLE chunk_resources (
                        world       TEXT NOT NULL,
                        cx          INTEGER NOT NULL,
                        cz          INTEGER NOT NULL,
                        material    TEXT NOT NULL,
                        per_hour    REAL NOT NULL,
                        PRIMARY KEY (world, cx, cz, material)
                    )"""),
            // 15: clan base levels
            List.of("""
                    CREATE TABLE clan_levels (
                        clan_id     TEXT PRIMARY KEY REFERENCES clans(id) ON DELETE CASCADE,
                        level       INTEGER NOT NULL
                    )"""),
            // 16: scouting cannot be lowered earlier than a day after raising it
            List.of("ALTER TABLE clan_scouting ADD COLUMN locked_until INTEGER NOT NULL DEFAULT 0"),
            // 17: how many collectors the clan has received in total (the first is free)
            List.of("""
                    CREATE TABLE clan_collectors_issued (
                        clan_id     TEXT PRIMARY KEY REFERENCES clans(id) ON DELETE CASCADE,
                        issued      INTEGER NOT NULL
                    )"""),
            // 18: chest access settings on clan territory
            List.of("""
                    CREATE TABLE chest_access (
                        world       TEXT NOT NULL,
                        x           INTEGER NOT NULL,
                        y           INTEGER NOT NULL,
                        z           INTEGER NOT NULL,
                        mode        TEXT NOT NULL,
                        tags        TEXT NOT NULL DEFAULT '',
                        PRIMARY KEY (world, x, y, z)
                    )"""),
            // 19: heart removal timer (base without upkeep or abandoned)
            List.of("""
                    CREATE TABLE heart_removals (
                        clan_id     TEXT PRIMARY KEY REFERENCES clans(id) ON DELETE CASCADE,
                        ends_at     INTEGER NOT NULL,
                        started_by  TEXT
                    )""")
    );

    private final Connection connection;

    private Database(Connection connection) {
        this.connection = connection;
    }

    public static Database open(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Class.forName("org.sqlite.JDBC");
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        Database db = new Database(connection);
        db.migrate();
        return db;
    }

    private void migrate() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            int version;
            try (ResultSet rs = st.executeQuery("PRAGMA user_version")) {
                version = rs.next() ? rs.getInt(1) : 0;
            }
            for (int i = version; i < MIGRATIONS.size(); i++) {
                connection.setAutoCommit(false);
                try {
                    for (String sql : MIGRATIONS.get(i)) {
                        st.execute(sql);
                    }
                    st.execute("PRAGMA user_version = " + (i + 1));
                    connection.commit();
                } catch (SQLException e) {
                    connection.rollback();
                    throw new SQLException("Schema migration " + (i + 1) + " failed", e);
                } finally {
                    connection.setAutoCommit(true);
                }
            }
        }
    }

    public Connection connection() {
        return connection;
    }

    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // The server is shutting down: nothing to do.
        }
    }
}
