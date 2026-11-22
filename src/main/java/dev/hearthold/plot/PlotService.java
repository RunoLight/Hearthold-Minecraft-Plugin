package dev.hearthold.plot;

import dev.hearthold.i18n.Lang;
import dev.hearthold.base.TerritoryService;
import dev.hearthold.clan.ClanException;
import dev.hearthold.clan.ClanService;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Plots, invites and build rating.
 * <p>
 * Score: for each material in the plot, value × √(count). The square root rewards variety
 * rather than filling with a single block. A plot's tier depends on its rank among all rated plots on the server
 * (top 3: 10, top 5%: 9, and so on). Clan prestige is the sum of the tiers of its best plots.
 */
public final class PlotService {

    private final Database db;
    private final TerritoryService territory;
    private final Supplier<HeartholdConfig> config;
    private final Logger log;
    private final Map<UUID, Plot> plots = new HashMap<>();

    public PlotService(Database db, ClanService clans, TerritoryService territory, Supplier<HeartholdConfig> config,
                       Logger log) {
        this.db = db;
        this.territory = territory;
        this.config = config;
        this.log = log;
        clans.onDisband(clan -> plots.values().removeIf(p -> p.clanId.equals(clan.id())));
    }

    public void loadAll() {
        Connection c = db.connection();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT id, clan_id, name, world, x, y, z, sx, sy, sz, score, rated_at, created_at FROM plots""");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Plot p = new Plot(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9),
                        rs.getInt(10), rs.getDouble(11), rs.getLong(12), rs.getLong(13));
                plots.put(p.id, p);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load plots", e);
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT plot_id, player_id FROM plot_members");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Plot p = plots.get(UUID.fromString(rs.getString(1)));
                if (p != null) {
                    p.members.add(UUID.fromString(rs.getString(2)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load plot members", e);
        }
    }

    public Optional<Plot> at(Block block) {
        return plots.values().stream().filter(p -> p.contains(block)).findFirst();
    }

    public Optional<Plot> heartAt(Block block) {
        return plots.values().stream().filter(p -> p.isHeart(block)).findFirst();
    }

    public List<Plot> of(UUID clanId) {
        return plots.values().stream().filter(p -> p.clanId.equals(clanId)).toList();
    }

    /** Places a plot of the given size (heart at the center); if it does not fit, explains why. */
    public Plot create(UUID clanId, Block heart, int sx, int sy, int sz) throws ClanException {
        HeartholdConfig.PlotSettings s = config.get().plot();
        if (of(clanId).size() >= s.maxPerClan()) {
            throw new ClanException(Lang.tr("plot.create.max", s.maxPerClan()));
        }
        checkSize(sx, sy, sz);
        long now = System.currentTimeMillis();
        Plot p = new Plot(UUID.randomUUID(), clanId, Lang.tr("plot.default-name", of(clanId).size() + 1), heart.getWorld().getName(),
                heart.getX(), heart.getY(), heart.getZ(), sx, sy, sz, 0, 0, now);
        String problem = fits(p, clanId, sx, sy, sz);
        if (problem != null) {
            throw new ClanException(problem + " " + Lang.tr("plot.create.size-hint", sx + "×" + sy + "×" + sz));
        }
        plots.put(p.id, p);
        try (PreparedStatement ps = db.connection().prepareStatement("""
                INSERT INTO plots (id, clan_id, name, world, x, y, z, sx, sy, sz, score, rated_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?)""")) {
            ps.setString(1, p.id.toString());
            ps.setString(2, clanId.toString());
            ps.setString(3, p.name);
            ps.setString(4, p.world);
            ps.setInt(5, p.x);
            ps.setInt(6, p.y);
            ps.setInt(7, p.z);
            ps.setInt(8, p.sx);
            ps.setInt(9, p.sy);
            ps.setInt(10, p.sz);
            ps.setLong(11, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            plots.remove(p.id);
            throw new ClanException(Lang.tr("plot.error.save"));
        }
        return p;
    }

    public void remove(Plot p) {
        plots.remove(p.id);
        exec("DELETE FROM plots WHERE id = ?", p.id.toString());
    }

    public void rename(Plot p, String name) throws ClanException {
        if (name.isBlank() || name.length() > 32) {
            throw new ClanException(Lang.tr("plot.name.invalid"));
        }
        p.name = name;
        exec("UPDATE plots SET name = ? WHERE id = ?", name, p.id.toString());
    }

    public int maxSize() {
        return config.get().plot().maxSize();
    }

    private void checkSize(int sx, int sy, int sz) throws ClanException {
        int max = maxSize();
        if (sx < 1 || sy < 1 || sz < 1 || sx > max || sy > max || sz > max) {
            throw new ClanException(Lang.tr("plot.size.range", max));
        }
    }

    public void resize(Plot p, int sx, int sy, int sz) throws ClanException {
        checkSize(sx, sy, sz);
        String problem = fits(p, p.clanId, sx, sy, sz);
        if (problem != null) {
            throw new ClanException(problem);
        }
        p.sx = sx;
        p.sy = sy;
        p.sz = sz;
        try (PreparedStatement ps = db.connection().prepareStatement(
                "UPDATE plots SET sx = ?, sy = ?, sz = ? WHERE id = ?")) {
            ps.setInt(1, sx);
            ps.setInt(2, sy);
            ps.setInt(3, sz);
            ps.setString(4, p.id.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ClanException(Lang.tr("plot.error.save"));
        }
    }

    /**
     * Why a plot of this size does not fit; null if it fits. Clan territory is a union of spheres,
     * so the whole surface of the box is checked, not just the corners: there can be gaps between spheres.
     */
    private String fits(Plot p, UUID clanId, int sx, int sy, int sz) {
        int minX = p.x - (sx - 1) / 2, minY = p.y - (sy - 1) / 2, minZ = p.z - (sz - 1) / 2;
        int maxX = minX + sx - 1, maxY = minY + sy - 1, maxZ = minZ + sz - 1;
        for (int cx = minX; cx <= maxX; cx++) {
            boolean edgeX = cx == minX || cx == maxX;
            for (int cy = minY; cy <= maxY; cy++) {
                boolean edgeY = cy == minY || cy == maxY;
                for (int cz = minZ; cz <= maxZ; cz++) {
                    if (!edgeX && !edgeY && cz != minZ && cz != maxZ) {
                        cz = maxZ - 1; // skip the interior: the next iteration is the far face
                        continue;
                    }
                    if (!clanId.equals(territory.claimAt(p.world, cx, cy, cz))) {
                        return Lang.tr("plot.fit.outside", cx, cy, cz);
                    }
                }
            }
        }
        for (Plot o : plots.values()) {
            if (o == p || !o.world.equals(p.world)) {
                continue;
            }
            boolean overlap = minX <= o.maxX() && maxX >= o.minX() && minY <= o.maxY() && maxY >= o.minY()
                    && minZ <= o.maxZ() && maxZ >= o.minZ();
            if (overlap) {
                return Lang.tr("plot.fit.overlap", o.name);
            }
        }
        return null;
    }

    /** Name of the plot the player is invited to (their "home"), or null. */
    public String homeOf(UUID player) {
        return plots.values().stream().filter(p -> p.members.contains(player)).map(p -> p.name)
                .sorted().findFirst().orElse(null);
    }

    public Optional<Plot> byId(UUID id) {
        return Optional.ofNullable(plots.get(id));
    }

    public void invite(Plot p, UUID player) {
        if (p.members.add(player)) {
            exec("INSERT OR IGNORE INTO plot_members (plot_id, player_id) VALUES (?, ?)", p.id.toString(), player.toString());
        }
    }

    public boolean kick(Plot p, UUID player) {
        if (!p.members.remove(player)) {
            return false;
        }
        exec("DELETE FROM plot_members WHERE plot_id = ? AND player_id = ?", p.id.toString(), player.toString());
        return true;
    }

    // --- Rating and prestige ---

    /** Recomputes the plot score from the blocks inside it. */
    public double rate(Plot p) throws ClanException {
        HeartholdConfig.PlotSettings s = config.get().plot();
        long cooldown = (long) (s.rateCooldownHours() * 3_600_000L);
        long now = System.currentTimeMillis();
        if (p.ratedAt > 0 && now - p.ratedAt < cooldown) {
            long h = (cooldown - (now - p.ratedAt) + 3_599_999) / 3_600_000;
            throw new ClanException(Lang.tr("plot.rate.cooldown", h));
        }
        World w = Bukkit.getWorld(p.world);
        if (w == null) {
            throw new ClanException(Lang.tr("plot.rate.world-unloaded"));
        }
        Map<Material, Integer> counts = new HashMap<>();
        for (int bx = p.minX(); bx <= p.maxX(); bx++) {
            for (int bz = p.minZ(); bz <= p.maxZ(); bz++) {
                if (!w.isChunkLoaded(bx >> 4, bz >> 4)) {
                    throw new ClanException(Lang.tr("plot.rate.chunks-unloaded"));
                }
                for (int by = Math.max(p.minY(), w.getMinHeight()); by <= Math.min(p.maxY(), w.getMaxHeight() - 1); by++) {
                    Material m = w.getBlockAt(bx, by, bz).getType();
                    if (!m.isAir() && !(bx == p.x && by == p.y && bz == p.z)) {
                        counts.merge(m, 1, Integer::sum);
                    }
                }
            }
        }
        double score = 0;
        for (var e : counts.entrySet()) {
            score += s.blockValues().get(e.getKey()) * Math.sqrt(e.getValue());
        }
        p.score = Math.round(score * 10) / 10.0;
        p.ratedAt = now;
        try (PreparedStatement ps = db.connection().prepareStatement(
                "UPDATE plots SET score = ?, rated_at = ? WHERE id = ?")) {
            ps.setDouble(1, p.score);
            ps.setLong(2, now);
            ps.setString(3, p.id.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to save plot score", e);
        }
        return p.score;
    }

    /** Rated plots on the server, best to worst. */
    public List<Plot> ranking() {
        List<Plot> list = new ArrayList<>(plots.values().stream().filter(p -> p.ratedAt > 0 && p.score > 0).toList());
        list.sort(Comparator.comparingDouble(Plot::score).reversed());
        return list;
    }

    /** Tier 1–10 by server rank; 0 if not rated. */
    public int tier(Plot p) {
        List<Plot> ranking = ranking();
        int index = ranking.indexOf(p);
        if (index < 0) {
            return 0;
        }
        if (index < 3) {
            return 10;
        }
        double pct = (index + 1) / (double) ranking.size();
        double[] bounds = {0.05, 0.10, 0.20, 0.30, 0.45, 0.60, 0.75, 0.90};
        for (int i = 0; i < bounds.length; i++) {
            if (pct <= bounds[i]) {
                return 9 - i;
            }
        }
        return 1;
    }

    /** Clan prestige: sum of the tiers of its best plots. */
    public int prestige(UUID clanId) {
        int n = config.get().plot().prestigePlots();
        return of(clanId).stream().mapToInt(this::tier).boxed()
                .sorted(Comparator.reverseOrder()).limit(n).mapToInt(Integer::intValue).sum();
    }

    private void exec(String sql, String... args) {
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Plot DB error", e);
        }
    }
}
