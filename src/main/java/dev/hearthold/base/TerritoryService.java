package dev.hearthold.base;

import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanException;
import dev.hearthold.clan.ClanService;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.config.Area;
import dev.hearthold.i18n.Lang;
import dev.hearthold.storage.Database;
import org.bukkit.block.Block;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Base spheres (hearts, nodes, proxies) and looking up a clan's territory by coordinates. */
public final class TerritoryService {

    private final Database db;
    private final ClanService clans;
    private final Supplier<HeartholdConfig> config;
    private final List<Sphere> spheres = new ArrayList<>();
    /** Spheres still waiting for activation; their time is checked every few seconds. */
    private final Set<UUID> pending = new HashSet<>();
    /** Whether the clan's territory protects blocks (e.g. whether upkeep is paid). */
    private Predicate<UUID> protectionFilter = clanId -> true;
    /** Cap on the clan's territory radius (newbie protection); MAX_VALUE means no cap. */
    private java.util.function.ToIntFunction<UUID> radiusLimit = clanId -> Integer.MAX_VALUE;
    /** Extra sphere placement check: the reason for denial, or null. */
    private java.util.function.BiFunction<Clan, Sphere.Kind, String> placementCheck = (c, k) -> null;

    public void setRadiusLimit(java.util.function.ToIntFunction<UUID> limit) {
        this.radiusLimit = limit;
    }

    public void setPlacementCheck(java.util.function.BiFunction<Clan, Sphere.Kind, String> check) {
        this.placementCheck = check;
    }

    public TerritoryService(Database db, ClanService clans, Supplier<HeartholdConfig> config) {
        this.db = db;
        this.clans = clans;
        this.config = config;
        clans.onDisband(clan -> {
            spheres.removeIf(s -> s.clanId().equals(clan.id()));
            pending.removeIf(id -> spheres.stream().noneMatch(s -> s.id().equals(id)));
        });
    }

    public void setProtectionFilter(Predicate<UUID> filter) {
        this.protectionFilter = filter;
    }

    public void loadAll() {
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT id, clan_id, kind, world, x, y, z, radius, placed_at, active_at FROM spheres");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Sphere s = new Sphere(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                        Sphere.Kind.valueOf(rs.getString(3)), rs.getString(4),
                        rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getLong(9), rs.getLong(10));
                spheres.add(s);
                if (!s.isActive(now)) {
                    pending.add(s.id());
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load base spheres", e);
        }
    }

    public Collection<Sphere> spheres() {
        return Collections.unmodifiableList(spheres);
    }

    public List<Sphere> spheresOf(UUID clanId) {
        return spheres.stream().filter(s -> s.clanId().equals(clanId)).toList();
    }

    public Optional<Sphere> heartOf(UUID clanId) {
        return spheres.stream()
                .filter(s -> s.clanId().equals(clanId) && s.kind() == Sphere.Kind.HEART)
                .findFirst();
    }

    public long nodeCount(UUID clanId) {
        return spheres.stream().filter(s -> s.clanId().equals(clanId) && s.kind() == Sphere.Kind.NODE).count();
    }

    public long proxyCount(UUID clanId) {
        return spheres.stream().filter(s -> s.clanId().equals(clanId) && s.kind() == Sphere.Kind.PROXY).count();
    }

    /** The heart, node or proxy located exactly at this block. */
    public Optional<Sphere> sphereAt(Block block) {
        return spheres.stream().filter(s -> s.isAt(block)).findFirst();
    }

    /**
     * The clan whose active, paid territory covers the block: only such territory gives raised block HP,
     * explosion protection and defender bonuses. Spheres of different clans never intersect, so there is at most one owner.
     */
    public Optional<Clan> ownerOf(Block block) {
        Sphere s = covering(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), true);
        return s == null || !protectionFilter.test(s.clanId()) ? Optional.empty() : clans.byId(s.clanId());
    }

    /**
     * Id of the clan owning the point (regardless of payment), or null. Includes spheres still activating:
     * the territory belongs to the clan right after placement.
     */
    public UUID claimAt(String world, int x, int y, int z) {
        Sphere s = covering(world, x, y, z, false);
        return s == null ? null : s.clanId();
    }

    /** Id of the clan whose already active territory covers the point (regeneration, HP, bonuses), or null. */
    public UUID activeClaimAt(String world, int x, int y, int z) {
        Sphere s = covering(world, x, y, z, true);
        return s == null ? null : s.clanId();
    }

    /** The clan owning the block (including still-activating spheres), regardless of upkeep payment. */
    public Optional<Clan> claimOf(Block block) {
        UUID id = claimAt(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        return id == null ? Optional.empty() : clans.byId(id);
    }

    /** The block is on clan territory whose sphere is still activating (no active sphere covers it). */
    public boolean isActivating(Block block) {
        String w = block.getWorld().getName();
        return covering(w, block.getX(), block.getY(), block.getZ(), false) != null
                && covering(w, block.getX(), block.getY(), block.getZ(), true) == null;
    }

    /** Time left until the sphere activates, as text: "12 min 30 s" or "1 h 05 min". */
    public static String timeLeft(Sphere s, long now) {
        long sec = Math.max(0, (s.activeAt() - now + 999) / 1000);
        if (sec >= 3600) {
            return Lang.tr("base.time.hours-minutes", sec / 3600, String.format(java.util.Locale.ROOT, "%02d", sec % 3600 / 60));
        }
        return sec >= 60 ? Lang.tr("base.time.minutes-seconds", sec / 60, sec % 60) : Lang.tr("base.time.seconds", sec);
    }

    /** The sphere covering the point; activeOnly limits it to already activated ones. */
    private Sphere covering(String world, int x, int y, int z, boolean activeOnly) {
        long now = System.currentTimeMillis();
        for (Sphere s : spheres) {
            if ((!activeOnly || s.isActive(now)) && s.covers(world, x, y, z, radiusLimit.applyAsInt(s.clanId()))) {
                return s;
            }
        }
        return null;
    }

    /**
     * Why the sphere cannot be removed (null if it can): after removal every node must stay connected through
     * intersecting spheres to the heart or proxy it grows from.
     */
    public String removalDenial(Sphere target) {
        if (target.kind() == Sphere.Kind.HEART) {
            return Lang.tr("base.remove.heart");
        }
        List<Sphere> rest = new ArrayList<>(spheresOf(target.clanId()));
        rest.removeIf(s -> s.id().equals(target.id()));
        Set<UUID> reached = new HashSet<>();
        java.util.ArrayDeque<Sphere> queue = new java.util.ArrayDeque<>();
        for (Sphere s : rest) {
            if (s.kind() != Sphere.Kind.NODE) {
                reached.add(s.id());
                queue.add(s);
            }
        }
        while (!queue.isEmpty()) {
            Sphere cur = queue.poll();
            for (Sphere s : rest) {
                if (!reached.contains(s.id()) && cur.intersects(s.world(), s.x(), s.y(), s.z(), s.radius())) {
                    reached.add(s.id());
                    queue.add(s);
                }
            }
        }
        long cut = rest.stream().filter(s -> !reached.contains(s.id())).count();
        if (cut > 0) {
            return Lang.tr("base.remove.cut", cut);
        }
        return null;
    }

    public Sphere place(Clan clan, Sphere.Kind kind, Block block) throws ClanException {
        HeartholdConfig c = config.get();
        HeartholdConfig.SphereSettings settings = c.sphere(kind);
        String world = block.getWorld().getName();
        int x = block.getX(), y = block.getY(), z = block.getZ();

        String denial = placementCheck.apply(clan, kind);
        if (denial != null) {
            throw new ClanException(denial);
        }
        if (kind == Sphere.Kind.HEART) {
            if (heartOf(clan.id()).isPresent()) {
                throw new ClanException(Lang.tr("base.place.heart-exists"));
            }
        } else if (kind == Sphere.Kind.PROXY) {
            if (heartOf(clan.id()).isEmpty()) {
                throw new ClanException(Lang.tr("base.place.heart-first"));
            }
            if (proxyCount(clan.id()) >= c.newbie().maxProxies()) {
                throw new ClanException(Lang.tr("base.place.max-proxies", c.newbie().maxProxies()));
            }
            for (Sphere own : spheresOf(clan.id())) {
                if (own.intersects(world, x, y, z, settings.radius())) {
                    throw new ClanException(Lang.tr("base.place.proxy-overlap", own.kind().title(), own.x(), own.z()));
                }
            }
        } else {
            if (heartOf(clan.id()).isEmpty()) {
                throw new ClanException(Lang.tr("base.place.heart-first"));
            }
            if (nodeCount(clan.id()) >= c.maxNodes()) {
                throw new ClanException(Lang.tr("base.place.max-nodes", c.maxNodes()));
            }
            boolean onOwnLand = spheresOf(clan.id()).stream().anyMatch(s -> s.covers(world, x, y, z));
            if (!onOwnLand) {
                throw new ClanException(Lang.tr("base.place.node-own-land"));
            }
        }
        for (Sphere other : spheres) {
            if (!other.clanId().equals(clan.id()) && other.intersects(world, x, y, z, settings.radius())) {
                throw new ClanException(Lang.tr("base.place.too-close"));
            }
        }
        for (Area zone : c.neutralZones()) {
            if (zone.touchesSphere(world, x, y, z, settings.radius())) {
                throw new ClanException(Lang.tr("base.place.neutral-zone", zone.name()));
            }
        }

        long now = System.currentTimeMillis();
        Sphere sphere = new Sphere(UUID.randomUUID(), clan.id(), kind, world, x, y, z, settings.radius(),
                now, now + settings.activationMinutes() * 60_000L);
        try (PreparedStatement ps = db.connection().prepareStatement("""
                INSERT INTO spheres (id, clan_id, kind, world, x, y, z, radius, placed_at, active_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, sphere.id().toString());
            ps.setString(2, sphere.clanId().toString());
            ps.setString(3, sphere.kind().name());
            ps.setString(4, sphere.world());
            ps.setInt(5, sphere.x());
            ps.setInt(6, sphere.y());
            ps.setInt(7, sphere.z());
            ps.setInt(8, sphere.radius());
            ps.setLong(9, sphere.placedAt());
            ps.setLong(10, sphere.activeAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ClanException(Lang.tr("base.save-error"));
        }
        spheres.add(sphere);
        if (!sphere.isActive(now)) {
            pending.add(sphere.id());
        }
        return sphere;
    }

    /** Removes a node or proxy without the connectivity check (the caller does it). The heart cannot be removed this way. */
    public void removeNode(Sphere node) throws ClanException {
        if (node.kind() == Sphere.Kind.HEART) {
            throw new ClanException(Lang.tr("base.remove.heart"));
        }
        if (spheres.stream().noneMatch(s -> s.id().equals(node.id()))) {
            return;
        }
        try (PreparedStatement ps = db.connection().prepareStatement("DELETE FROM spheres WHERE id = ?")) {
            ps.setString(1, node.id().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ClanException(Lang.tr("base.save-error"));
        }
        spheres.remove(node);
        pending.remove(node.id());
    }

    /** Removes any sphere, including the heart (heart removal timer). Does not touch the block in the world. */
    void removeAny(Sphere s) {
        try (PreparedStatement ps = db.connection().prepareStatement("DELETE FROM spheres WHERE id = ?")) {
            ps.setString(1, s.id().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            java.util.logging.Logger.getLogger("Hearthold").log(java.util.logging.Level.WARNING, "Failed to delete sphere", e);
        }
        spheres.remove(s);
        pending.remove(s.id());
    }

    /** Clans whose spheres are within alert-radius of the point horizontally (excluding the clan itself). */
    public Set<UUID> neighbours(UUID clanId, String world, int x, int z) {
        int radius = config.get().alertRadius();
        Set<UUID> result = new HashSet<>();
        for (Sphere s : spheres) {
            if (!s.clanId().equals(clanId) && s.horizontalDistance(world, x, z) <= radius + s.radius()) {
                result.add(s.clanId());
            }
        }
        return result;
    }

    /** For testing: activates all of the clan's pending spheres immediately. Returns how many. */
    public int activateNow(UUID clanId) {
        long now = System.currentTimeMillis();
        int count = 0;
        for (int i = 0; i < spheres.size(); i++) {
            Sphere s = spheres.get(i);
            if (!s.clanId().equals(clanId) || s.isActive(now)) {
                continue;
            }
            spheres.set(i, new Sphere(s.id(), s.clanId(), s.kind(), s.world(), s.x(), s.y(), s.z(), s.radius(),
                    s.placedAt(), now));
            try (PreparedStatement ps = db.connection().prepareStatement("UPDATE spheres SET active_at = ? WHERE id = ?")) {
                ps.setLong(1, now);
                ps.setString(2, s.id().toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to save sphere activation", e);
            }
            count++;
        }
        return count;
    }

    /** Spheres whose activation time has come since the previous call. */
    public List<Sphere> pollActivated() {
        if (pending.isEmpty()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<Sphere> activated = spheres.stream()
                .filter(s -> pending.contains(s.id()) && s.isActive(now))
                .toList();
        activated.forEach(s -> pending.remove(s.id()));
        return activated;
    }
}
