package dev.hearthold.plot;

import dev.hearthold.i18n.Lang;
import dev.hearthold.clan.Clan;
import dev.hearthold.storage.Database;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.InventoryHolder;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Access to chests (and other containers) on clan territory. Clan leaders can always open everything.
 * Default: inside a plot, plot members; outside plots, the whole clan.
 */
public final class ChestAccess {

    public enum Mode {
        EVERYONE, CLAN, PLOT, TAGS;

        public String title() {
            return Lang.tr("plot.chest.mode." + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** Setting of a single container. */
    public record Setting(Mode mode, Set<Clan.Tag> tags) {

        public String describe() {
            if (mode != Mode.TAGS) {
                return mode.title();
            }
            return tags.isEmpty() ? Lang.tr("plot.chest.leaders-only")
                    : Lang.tr("plot.chest.tags", tags.stream().map(Clan.Tag::title).collect(Collectors.joining(", ")));
        }
    }

    private record Key(String world, int x, int y, int z) {
        static Key of(Block b) {
            return new Key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        }
    }

    private final Database db;
    private final Logger log;
    private final Map<Key, Setting> settings = new HashMap<>();

    public ChestAccess(Database db, Logger log) {
        this.db = db;
        this.log = log;
    }

    public void loadAll() {
        try (PreparedStatement ps = db.connection().prepareStatement("SELECT world, x, y, z, mode, tags FROM chest_access");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                EnumSet<Clan.Tag> tags = EnumSet.noneOf(Clan.Tag.class);
                for (String t : rs.getString(6).split(",")) {
                    if (!t.isBlank()) {
                        try {
                            tags.add(Clan.Tag.valueOf(t));
                        } catch (IllegalArgumentException ignored) {
                            // tag was removed from the code
                        }
                    }
                }
                settings.put(new Key(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)),
                        new Setting(Mode.valueOf(rs.getString(5)), tags));
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to load chest access settings", e);
        }
    }

    /** Container setting, or the default (inside a plot: plot members, otherwise: the clan). */
    public Setting of(Block block, boolean inPlot) {
        for (Block b : halves(block)) {
            Setting s = settings.get(Key.of(b));
            if (s != null) {
                return s;
            }
        }
        return new Setting(inPlot ? Mode.PLOT : Mode.CLAN, Set.of());
    }

    /** Whether an explicit setting exists (otherwise the default applies). */
    public boolean isCustom(Block block) {
        return halves(block).stream().anyMatch(b -> settings.containsKey(Key.of(b)));
    }

    public void set(Block block, Setting setting) {
        for (Block b : halves(block)) {
            Key k = Key.of(b);
            settings.put(k, setting);
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "INSERT OR REPLACE INTO chest_access (world, x, y, z, mode, tags) VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, k.world());
                ps.setInt(2, k.x());
                ps.setInt(3, k.y());
                ps.setInt(4, k.z());
                ps.setString(5, setting.mode().name());
                ps.setString(6, setting.tags().stream().map(Enum::name).collect(Collectors.joining(",")));
                ps.executeUpdate();
            } catch (SQLException e) {
                log.log(Level.WARNING, "Failed to save chest access", e);
            }
        }
    }

    /** The chest was broken; this block's setting is no longer needed. */
    public void forget(Block block) {
        Key k = Key.of(block);
        if (settings.remove(k) == null) {
            return;
        }
        try (PreparedStatement ps = db.connection().prepareStatement(
                "DELETE FROM chest_access WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
            ps.setString(1, k.world());
            ps.setInt(2, k.x());
            ps.setInt(3, k.y());
            ps.setInt(4, k.z());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to delete chest access", e);
        }
    }

    /** The block and, for a double chest, its other half. */
    private static List<Block> halves(Block block) {
        List<Block> list = new ArrayList<>(2);
        list.add(block);
        if (block.getState(false) instanceof Chest chest) {
            InventoryHolder holder = chest.getInventory().getHolder();
            if (holder instanceof DoubleChest dc) {
                for (InventoryHolder side : new InventoryHolder[]{dc.getLeftSide(), dc.getRightSide()}) {
                    if (side instanceof Chest c && !c.getBlock().equals(block)) {
                        list.add(c.getBlock());
                    }
                }
            }
        }
        return list;
    }
}
