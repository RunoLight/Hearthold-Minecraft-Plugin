package dev.hearthold.base;

import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanException;
import dev.hearthold.clan.ClanService;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.i18n.Lang;
import dev.hearthold.storage.Database;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Heart removal timer. A base without upkeep does not live forever: its clan or suzerain (whoever captured it)
 * can start a timer in the heart menu (7 days by default), after which the heart and nodes disappear.
 * For an abandoned clan (no members) the timer starts by itself. Paying upkeep cancels the timer, and so does
 * claiming an abandoned base. The state is shown above the heart, in the clan list, on the HUD and on the website.
 */
public final class HeartRemovalService {

    /** startedBy is the clan that started the timer; null means it started by itself (base abandoned). */
    public record Timer(UUID clanId, long endsAt, UUID startedBy) { }

    private final Database db;
    private final ClanService clans;
    private final TerritoryService territory;
    private final Supplier<HeartholdConfig> config;
    private final Logger log;
    private final Map<UUID, Timer> timers = new HashMap<>();
    /** Label above the heart: clan → TextDisplay entity. */
    private final Map<UUID, UUID> displays = new HashMap<>();
    private Predicate<UUID> isProtected = id -> true;
    /** (suzerain, vassal): whether the first clan is the second one's suzerain. */
    private BiPredicate<UUID, UUID> isSuzerain = (a, b) -> false;

    public HeartRemovalService(Database db, ClanService clans, TerritoryService territory,
                               Supplier<HeartholdConfig> config, Logger log) {
        this.db = db;
        this.clans = clans;
        this.territory = territory;
        this.config = config;
        this.log = log;
        clans.onDisband(c -> {
            timers.remove(c.id());
            removeDisplay(c.id());
        });
        clans.onAbandon(this::onAbandoned);
    }

    public void setProtection(Predicate<UUID> isProtected) {
        this.isProtected = isProtected;
    }

    public void setSuzerainCheck(BiPredicate<UUID, UUID> isSuzerain) {
        this.isSuzerain = isSuzerain;
    }

    /** Whether clan a is the suzerain of clan b. */
    public boolean isSuzerain(UUID a, UUID b) {
        return a != null && isSuzerain.test(a, b);
    }

    public void loadAll() {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT clan_id, ends_at, started_by FROM heart_removals");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String by = rs.getString(3);
                Timer t = new Timer(UUID.fromString(rs.getString(1)), rs.getLong(2), by == null ? null : UUID.fromString(by));
                timers.put(t.clanId(), t);
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to load heart removal timers", e);
        }
    }

    public Optional<Timer> of(UUID clanId) {
        return Optional.ofNullable(timers.get(clanId));
    }

    /** Whether the clan is abandoned: no members but a base exists. */
    public boolean isAbandoned(Clan clan) {
        return clan.members().isEmpty() && territory.heartOf(clan.id()).isPresent();
    }

    /** Status line for the HUD, clan list and website; null if there is nothing special. */
    public String notice(UUID clanId) {
        Optional<Clan> clan = clans.byId(clanId);
        if (clan.isEmpty()) {
            return null;
        }
        Timer t = timers.get(clanId);
        boolean abandoned = isAbandoned(clan.get());
        if (t == null) {
            return abandoned ? Lang.tr("base.removal.notice.abandoned") : null;
        }
        String left = left(t.endsAt() - System.currentTimeMillis());
        return Lang.tr(abandoned ? "base.removal.notice.abandoned-timer" : "base.removal.notice.timer", left);
    }

    public static String left(long ms) {
        long minutes = Math.max(0, ms / 60_000);
        long days = minutes / 1440;
        long hours = minutes % 1440 / 60;
        if (days > 0) {
            return Lang.tr("base.time.days-hours", days, hours);
        }
        return hours > 0 ? Lang.tr("base.time.hours-minutes", hours, minutes % 60)
                : Lang.tr("base.time.minutes", Math.max(1, minutes));
    }

    /**
     * Why clan byClan cannot start the timer for clan target (null if it can): the base must be without
     * upkeep, and it is started by the clan itself or its suzerain.
     */
    public String startDenial(Clan target, UUID byClan) {
        if (territory.heartOf(target.id()).isEmpty()) {
            return Lang.tr("base.removal.deny.no-heart");
        }
        if (timers.containsKey(target.id())) {
            return Lang.tr("base.removal.deny.running");
        }
        if (!target.id().equals(byClan) && !isSuzerain.test(byClan, target.id())) {
            return Lang.tr("base.removal.deny.who");
        }
        if (isProtected.test(target.id())) {
            return Lang.tr("base.removal.deny.paid");
        }
        return null;
    }

    public void start(Clan target, UUID byClan) throws ClanException {
        String denial = startDenial(target, byClan);
        if (denial != null) {
            throw new ClanException(denial);
        }
        put(target, byClan);
        String by = clans.byId(byClan).map(Clan::name).orElse("?");
        Component msg = Component.text(Lang.tr("base.removal.started", by, target.name(),
                left(timers.get(target.id()).endsAt() - System.currentTimeMillis())), NamedTextColor.GOLD);
        ClanService.broadcast(target, msg);
        clans.byId(byClan).filter(c -> !c.id().equals(target.id())).ifPresent(c -> ClanService.broadcast(c, msg));
    }

    /** Only the clan that started the timer can cancel it (an abandoned-base timer is cancelled only by claiming the base). */
    public String cancelDenial(Clan target, UUID byClan) {
        Timer t = timers.get(target.id());
        if (t == null) {
            return Lang.tr("base.removal.deny.not-running");
        }
        if (t.startedBy() == null || !t.startedBy().equals(byClan)) {
            return Lang.tr("base.removal.deny.cancel-who");
        }
        return null;
    }

    public void cancel(Clan target, UUID byClan) throws ClanException {
        String denial = cancelDenial(target, byClan);
        if (denial != null) {
            throw new ClanException(denial);
        }
        stop(target, Lang.tr("base.removal.reason.cancelled"));
    }

    private void put(Clan target, UUID byClan) {
        long ends = System.currentTimeMillis() + (long) (config.get().heartRemovalDays() * 86_400_000L);
        Timer t = new Timer(target.id(), ends, byClan);
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT OR REPLACE INTO heart_removals (clan_id, ends_at, started_by) VALUES (?, ?, ?)")) {
            ps.setString(1, t.clanId().toString());
            ps.setLong(2, t.endsAt());
            ps.setString(3, byClan == null ? null : byClan.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to save heart removal timer", e);
        }
        timers.put(t.clanId(), t);
        updateDisplay(target);
    }

    private void stop(Clan clan, String why) {
        if (timers.remove(clan.id()) == null) {
            return;
        }
        try (PreparedStatement ps = db.connection().prepareStatement("DELETE FROM heart_removals WHERE clan_id = ?")) {
            ps.setString(1, clan.id().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to delete heart removal timer", e);
        }
        ClanService.broadcast(clan, Component.text(Lang.tr("base.removal.stopped", why), NamedTextColor.GREEN));
        updateDisplay(clan);
    }

    private void onAbandoned(Clan clan) {
        if (territory.heartOf(clan.id()).isPresent() && !timers.containsKey(clan.id())) {
            put(clan, null);
            Bukkit.broadcast(Component.text(Lang.tr("base.removal.abandoned", clan.name(),
                    left(timers.get(clan.id()).endsAt() - System.currentTimeMillis())), NamedTextColor.GOLD));
        }
    }

    /** An abandoned base was claimed: the self-started timer is removed. */
    public void onClaimed(Clan clan) {
        Timer t = timers.get(clan.id());
        if (t != null && t.startedBy() == null) {
            stop(clan, Lang.tr("base.removal.reason.claimed"));
        }
        updateDisplay(clan);
    }

    /** Every few seconds: automatic timers, cancellation on payment, removal on expiry, labels above hearts. */
    public void tick() {
        long now = System.currentTimeMillis();
        for (Clan clan : List.copyOf(clans.all())) {
            boolean empty = clan.members().isEmpty();
            if (territory.heartOf(clan.id()).isEmpty()) {
                if (empty) {
                    disband(clan); // an abandoned clan without a base is no longer needed
                } else if (timers.containsKey(clan.id())) {
                    stop(clan, Lang.tr("base.removal.reason.no-heart"));
                }
                continue;
            }
            Timer t = timers.get(clan.id());
            if (empty && t == null) {
                onAbandoned(clan);
                t = timers.get(clan.id());
            } else if (!empty && t != null && t.startedBy() == null) {
                stop(clan, Lang.tr("base.removal.reason.claimed"));
                t = null;
            } else if (!empty && t != null && isProtected.test(clan.id())) {
                stop(clan, Lang.tr("base.removal.reason.paid"));
                t = null;
            }
            if (t != null && now >= t.endsAt()) {
                removeBase(clan);
                continue;
            }
            updateDisplay(clan);
        }
    }

    /** The heart and nodes disappear (proxies, being separate bases, stay). An abandoned clan is disbanded entirely. */
    private void removeBase(Clan clan) {
        boolean empty = clan.members().isEmpty();
        for (Sphere s : territory.spheresOf(clan.id())) {
            if (!empty && s.kind() == Sphere.Kind.PROXY) {
                continue;
            }
            World w = Bukkit.getWorld(s.world());
            if (w != null) {
                w.getBlockAt(s.x(), s.y(), s.z()).setType(Material.AIR);
            }
            territory.removeAny(s);
        }
        removeDisplay(clan.id());
        stop(clan, Lang.tr("base.removal.reason.removed"));
        Bukkit.broadcast(Component.text(Lang.tr("base.removal.removed", clan.name()), NamedTextColor.GOLD));
        if (empty) {
            disband(clan);
        }
    }

    private void disband(Clan clan) {
        try {
            clans.forceDisband(clan);
        } catch (ClanException e) {
            log.log(Level.WARNING, "Failed to disband abandoned clan " + clan.name(), e);
        }
    }

    // --- Label above the heart ---

    private void updateDisplay(Clan clan) {
        String text = notice(clan.id());
        Optional<Sphere> heart = territory.heartOf(clan.id());
        if (text == null || heart.isEmpty()) {
            removeDisplay(clan.id());
            return;
        }
        World w = Bukkit.getWorld(heart.get().world());
        if (w == null || !w.isChunkLoaded(heart.get().x() >> 4, heart.get().z() >> 4)) {
            return;
        }
        Component line = Component.text(clan.name() + "\n", NamedTextColor.WHITE)
                .append(Component.text(text, NamedTextColor.GOLD));
        if (isAbandoned(clan)) {
            line = line.append(Component.text("\n" + Lang.tr("base.removal.display.claim-hint"), NamedTextColor.GRAY));
        }
        UUID id = displays.get(clan.id());
        Entity e = id == null ? null : Bukkit.getEntity(id);
        if (e instanceof TextDisplay td && td.isValid()) {
            td.text(line);
            return;
        }
        Location at = new Location(w, heart.get().x() + 0.5, heart.get().y() + 1.8, heart.get().z() + 0.5);
        Component finalLine = line;
        TextDisplay td = w.spawn(at, TextDisplay.class, d -> {
            d.text(finalLine);
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(false);
            d.setSeeThrough(false);
        });
        displays.put(clan.id(), td.getUniqueId());
    }

    private void removeDisplay(UUID clanId) {
        UUID id = displays.remove(clanId);
        Entity e = id == null ? null : Bukkit.getEntity(id);
        if (e != null) {
            e.remove();
        }
    }

    public void removeAllDisplays() {
        List.copyOf(displays.keySet()).forEach(this::removeDisplay);
    }
}
