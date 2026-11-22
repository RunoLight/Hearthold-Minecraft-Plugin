package dev.hearthold.war;

import dev.hearthold.i18n.Lang;
import dev.hearthold.base.Sphere;
import dev.hearthold.base.TerritoryService;
import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanService;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.health.PrimeTimeClock;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Heart capture works like a capture point. During an active war and in prime time, attackers stand within
 * the defender's heart radius; the progress bar is visible to both sides.
 * <ul>
 *   <li>Attackers present, no living defenders: progress grows by 1 s per second.</li>
 *   <li>Both present: progress holds.</li>
 *   <li>No attackers: progress decays.</li>
 * </ul>
 * Full progress ends the war; the defender becomes a vassal and gets a shield.
 * <p>
 * A base without upkeep (or an abandoned one) can be captured without war: stand at the heart, in prime time.
 * The capturing clan is the one whose players stand at the heart alone; with two foreign clans or a defender there,
 * progress holds.
 * <p>
 * During a capture the heart emits particles and sound; the pitch rises with progress.
 */
public final class CaptureTask implements Runnable {

    private final WarService wars;
    private final VassalService vassals;
    private final TerritoryService territory;
    private final ClanService clans;
    private final PrimeTimeClock primeTime;
    private final Supplier<HeartholdConfig> config;
    private final Map<UUID, Double> progress = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    /** Capture of bases without upkeep: target clan → capturing clan. */
    private final Map<UUID, UUID> freeCapturer = new HashMap<>();
    private java.util.function.Predicate<UUID> isProtected = id -> true;
    private java.util.function.BiPredicate<UUID, UUID> allies = (a, b) -> false;
    /** Test mode: capture in 10 s, at any time. */
    private boolean fast;
    public static final int FAST_SECONDS = 10;

    public void setProtection(java.util.function.Predicate<UUID> isProtected) {
        this.isProtected = isProtected;
    }

    /** Who cannot be captured without war: newbie protection, post-siege shield. */
    private java.util.function.Predicate<UUID> immune = id -> false;

    public void setImmune(java.util.function.Predicate<UUID> immune) {
        this.immune = immune;
    }

    public void setAllies(java.util.function.BiPredicate<UUID, UUID> allies) {
        this.allies = allies;
    }

    public boolean isFast() {
        return fast;
    }

    public void setFast(boolean fast) {
        this.fast = fast;
    }

    private int captureSeconds(HeartholdConfig.WarSettings ws) {
        return fast ? FAST_SECONDS : ws.captureSeconds();
    }

    private boolean primeOk() {
        return fast || primeTime.isActive();
    }

    public CaptureTask(WarService wars, VassalService vassals, TerritoryService territory, ClanService clans,
                       PrimeTimeClock primeTime, Supplier<HeartholdConfig> config) {
        this.wars = wars;
        this.vassals = vassals;
        this.territory = territory;
        this.clans = clans;
        this.primeTime = primeTime;
        this.config = config;
    }

    public double progress(UUID warId) {
        return progress.getOrDefault(warId, 0.0);
    }

    @Override
    public void run() {
        runWars();
        runFree(config.get().war());
    }

    private void runWars() {
        HeartholdConfig.WarSettings ws = config.get().war();
        long now = System.currentTimeMillis();
        // Remove bars of wars that have already ended.
        bars.keySet().removeIf(id -> {
            if (!freeCapturer.containsKey(id) && wars.ongoing().stream().noneMatch(w -> w.id().equals(id))) {
                hide(bars.get(id));
                progress.remove(id);
                return true;
            }
            return false;
        });

        for (War w : wars.ongoing()) {
            if (!w.isActive(now)) {
                continue;
            }
            Optional<Sphere> heart = territory.heartOf(w.defender());
            Optional<Clan> attacker = clans.byId(w.attacker());
            Optional<Clan> defender = clans.byId(w.defender());
            if (heart.isEmpty() || attacker.isEmpty() || defender.isEmpty()) {
                continue;
            }
            World world = Bukkit.getWorld(heart.get().world());
            if (world == null) {
                continue;
            }
            Location center = new Location(world, heart.get().x() + 0.5, heart.get().y() + 0.5, heart.get().z() + 0.5);
            int attackers = count(attacker.get(), center, ws.captureRadius());
            int defenders = count(defender.get(), center, ws.captureRadius());

            double p = progress.getOrDefault(w.id(), 0.0);
            String state;
            boolean going = false;
            if (!primeOk()) {
                state = Lang.tr("war.capture.state.off-prime");
            } else if (attackers > 0 && defenders == 0) {
                p += 1;
                going = true;
                state = Lang.tr("war.capture.state.going");
            } else if (attackers > 0) {
                state = Lang.tr("war.capture.state.defenders");
            } else {
                p = Math.max(0, p - ws.captureDecay());
                state = Lang.tr("war.capture.state.decaying");
            }
            progress.put(w.id(), p);
            effects(center, p, captureSeconds(ws), going, ws.captureRadius());

            if (p >= captureSeconds(ws)) {
                celebrate(center);
                capture(w, attacker.get(), defender.get(), ws);
                continue;
            }
            BossBar bar = bars.computeIfAbsent(w.id(), id -> BossBar.bossBar(Component.empty(), 0f,
                    BossBar.Color.RED, BossBar.Overlay.NOTCHED_10));
            if (p <= 0) {
                hide(bar);
                continue;
            }
            bar.name(Component.text(Lang.tr("war.capture.bar", defender.get().name(), state), NamedTextColor.WHITE));
            bar.progress((float) Math.min(1.0, p / captureSeconds(ws)));
            for (Clan c : new Clan[]{attacker.get(), defender.get()}) {
                for (UUID id : c.members().keySet()) {
                    Player pl = Bukkit.getPlayer(id);
                    if (pl != null) {
                        pl.showBossBar(bar);
                    }
                }
            }
        }
    }

    /** Capture of bases without upkeep (and abandoned ones): no war needed, the clan standing at the heart alone captures. */
    private void runFree(HeartholdConfig.WarSettings ws) {
        long now = System.currentTimeMillis();
        for (Clan target : List.copyOf(clans.all())) {
            UUID key = target.id();
            Optional<Sphere> heart = territory.heartOf(target.id());
            boolean open = heart.isPresent() && heart.get().isActive(now)
                    && (target.members().isEmpty() || !isProtected.test(target.id()))
                    && !immune.test(target.id())
                    && wars.ongoing().stream().noneMatch(w -> w.defender().equals(target.id()));
            World world = heart.map(h -> Bukkit.getWorld(h.world())).orElse(null);
            if (!open || world == null) {
                dropFree(key);
                continue;
            }
            Location center = new Location(world, heart.get().x() + 0.5, heart.get().y() + 0.5, heart.get().z() + 0.5);
            double r2 = (double) ws.captureRadius() * ws.captureRadius();
            // Which clans stand at the heart (the target's allies and its current suzerain do not capture).
            java.util.Set<UUID> present = new java.util.HashSet<>();
            for (Player pl : world.getPlayers()) {
                if (pl.isDead() || pl.getGameMode() == GameMode.SPECTATOR || pl.getGameMode() == GameMode.CREATIVE
                        || pl.getLocation().distanceSquared(center) > r2) {
                    continue;
                }
                clans.ofPlayer(pl.getUniqueId()).map(Clan::id).ifPresent(present::add);
            }
            boolean defenders = present.remove(target.id());
            present.removeIf(id -> allies.test(id, target.id())
                    || vassals.suzerainOf(target.id()).filter(v -> v.suzerain().equals(id)).isPresent());
            double p = progress.getOrDefault(key, 0.0);
            String state;
            boolean going = false;
            UUID capturer = freeCapturer.get(key);
            if (present.isEmpty()) {
                p = Math.max(0, p - ws.captureDecay());
                state = Lang.tr("war.capture.state.decaying");
            } else if (!primeOk()) {
                state = Lang.tr("war.capture.state.off-prime");
            } else if (defenders) {
                state = Lang.tr("war.capture.state.defenders");
            } else if (present.size() > 1) {
                state = Lang.tr("war.capture.state.contested");
            } else {
                UUID who = present.iterator().next();
                if (!who.equals(capturer)) {
                    p = 0;
                    capturer = who;
                    freeCapturer.put(key, who);
                }
                p += 1;
                going = true;
                state = Lang.tr("war.capture.state.going");
            }
            progress.put(key, p);
            Optional<Clan> attacker = capturer == null ? Optional.empty() : clans.byId(capturer);
            if (p <= 0 || attacker.isEmpty()) {
                dropFree(key);
                continue;
            }
            effects(center, p, captureSeconds(ws), going, ws.captureRadius());
            if (p >= captureSeconds(ws)) {
                dropFree(key);
                celebrate(center);
                vassals.create(attacker.get(), target, VassalService.Kind.CONQUEST, ws.vassalDays());
                Bukkit.broadcast(Component.text(Lang.tr("war.capture.free-captured", attacker.get().name(),
                        target.name()), NamedTextColor.GOLD));
                continue;
            }
            BossBar bar = bars.computeIfAbsent(key, id -> BossBar.bossBar(Component.empty(), 0f,
                    BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_10));
            bar.name(Component.text(Lang.tr("war.capture.free-bar", target.name(), state), NamedTextColor.WHITE));
            bar.progress((float) Math.min(1.0, p / captureSeconds(ws)));
            java.util.Set<UUID> viewers = new java.util.HashSet<>(attacker.get().members().keySet());
            viewers.addAll(target.members().keySet());
            for (Player pl : world.getPlayers()) {
                if (pl.getLocation().distanceSquared(center) < 48 * 48) {
                    viewers.add(pl.getUniqueId());
                }
            }
            for (UUID id : viewers) {
                Player pl = Bukkit.getPlayer(id);
                if (pl != null) {
                    pl.showBossBar(bar);
                }
            }
        }
    }

    private void dropFree(UUID key) {
        freeCapturer.remove(key);
        if (progress.remove(key) != null || bars.containsKey(key)) {
            BossBar bar = bars.remove(key);
            if (bar != null) {
                hide(bar);
            }
        }
    }

    /**
     * Capture effects once per second: a particle ring at the capture radius and a fire column above the heart,
     * a bell sound (pitch rises with progress). If the capture holds or decays: a grey ring and smoke.
     */
    private static void effects(Location center, double p, int total, boolean going, int radius) {
        World w = center.getWorld();
        if (p <= 0 || w == null) {
            return;
        }
        double ratio = Math.min(1.0, p / total);
        int points = Math.max(16, radius * 10);
        var dust = new org.bukkit.Particle.DustOptions(going ? org.bukkit.Color.RED : org.bukkit.Color.GRAY, 1.4f);
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            w.spawnParticle(org.bukkit.Particle.DUST, center.getX() + Math.cos(a) * radius, center.getY() - 0.3,
                    center.getZ() + Math.sin(a) * radius, 1, dust);
        }
        if (going) {
            int height = 2 + (int) Math.round(ratio * 10);
            for (int y = 0; y < height; y++) {
                w.spawnParticle(org.bukkit.Particle.SOUL_FIRE_FLAME, center.getX(), center.getY() + 0.8 + y * 0.5,
                        center.getZ(), 2, 0.15, 0.1, 0.15, 0.01);
            }
            w.playSound(center, org.bukkit.Sound.BLOCK_NOTE_BLOCK_BELL, org.bukkit.SoundCategory.BLOCKS, 1.2f,
                    (float) (0.5 + ratio * 1.5));
            w.playSound(center, org.bukkit.Sound.BLOCK_BEACON_AMBIENT, org.bukkit.SoundCategory.BLOCKS, 0.8f, 1.0f);
        } else {
            w.spawnParticle(org.bukkit.Particle.LARGE_SMOKE, center.getX(), center.getY() + 1, center.getZ(),
                    6, 0.3, 0.3, 0.3, 0.01);
        }
    }

    /** Capture complete: a flash and a loud sound for the whole area. */
    private static void celebrate(Location center) {
        World w = center.getWorld();
        if (w == null) {
            return;
        }
        w.spawnParticle(org.bukkit.Particle.FIREWORK, center.getX(), center.getY() + 1, center.getZ(), 120, 0.6, 1.2, 0.6, 0.15);
        w.spawnParticle(org.bukkit.Particle.TOTEM_OF_UNDYING, center.getX(), center.getY() + 1, center.getZ(), 80, 0.5, 1, 0.5, 0.4);
        w.playSound(center, org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, org.bukkit.SoundCategory.MASTER, 3f, 1f);
        w.playSound(center, org.bukkit.Sound.ENTITY_LIGHTNING_BOLT_THUNDER, org.bukkit.SoundCategory.MASTER, 2f, 1.2f);
    }

    private void capture(War w, Clan attacker, Clan defender, HeartholdConfig.WarSettings ws) {
        progress.remove(w.id());
        BossBar bar = bars.remove(w.id());
        if (bar != null) {
            hide(bar);
        }
        wars.end(w, War.Outcome.CAPTURED);
        vassals.create(attacker, defender, VassalService.Kind.CONQUEST, ws.vassalDays());
        wars.shield(defender.id(), ws.shieldHours());
    }

    /** Living clan members within the radius of the center (spectators and creative do not count). */
    private static int count(Clan clan, Location center, int radius) {
        int n = 0;
        double r2 = (double) radius * radius;
        for (UUID id : clan.members().keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || p.isDead() || !p.getWorld().equals(center.getWorld())) {
                continue;
            }
            if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) {
                continue;
            }
            if (p.getLocation().distanceSquared(center) <= r2) {
                n++;
            }
        }
        return n;
    }

    private static void hide(BossBar bar) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.hideBossBar(bar);
        }
    }

    public void hideAll() {
        bars.values().forEach(CaptureTask::hide);
    }
}
