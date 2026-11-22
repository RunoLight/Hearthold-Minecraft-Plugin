package dev.hearthold.event;

import dev.hearthold.config.EventConfig;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.i18n.Lang;
import dev.hearthold.storage.Database;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Events at sites placed by an admin (structures are built on the map beforehand).
 * <ul>
 *   <li><b>Waves</b> (usually in the Nether): ready → a player right-clicks the core → mob waves → reward at the
 *       core → cooldown. If everyone leaves or a wave is not cleared in time, the event fails.</li>
 *   <li><b>Chests</b> (overworld): waiting → announcement with a timer (visible to all) → chests in the radius
 *       are filled with loot and opened, a trader may appear → closing.</li>
 * </ul>
 */
public final class EventService {

    public enum Type { WAVE, CHEST }

    public enum State {
        READY,
        ACTIVE,
        COOLDOWN,
        WAITING,
        ANNOUNCED,
        OPEN;

        public String title() {
            return Lang.tr("event.state." + name().toLowerCase(Locale.ROOT));
        }
    }

    public static final class Site {
        final String name;
        final Type type;
        final String world;
        final int x, y, z;
        final int radius;
        State state;
        long nextAt;
        // Transient wave state.
        int wave;
        long waveStartedAt;
        long lastPlayerSeenAt;
        final Set<UUID> mobs = new HashSet<>();
        UUID trader;
        long traderUntil;

        Site(String name, Type type, String world, int x, int y, int z, int radius, State state, long nextAt) {
            this.name = name;
            this.type = type;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.radius = radius;
            this.state = state;
            this.nextAt = nextAt;
        }

        public String name() {
            return name;
        }

        public Type type() {
            return type;
        }

        public State state() {
            return state;
        }

        public long nextAt() {
            return nextAt;
        }

        public int wave() {
            return wave;
        }

        public String world() {
            return world;
        }

        public int x() {
            return x;
        }

        public int z() {
            return z;
        }

        public int radius() {
            return radius;
        }

        public String coords() {
            return world + " " + x + ", " + y + ", " + z;
        }

        public Optional<Location> core() {
            World w = Bukkit.getWorld(world);
            return w == null ? Optional.empty() : Optional.of(new Location(w, x + 0.5, y, z + 0.5));
        }

        public boolean isCore(Block b) {
            return b.getWorld().getName().equals(world) && b.getX() == x && b.getY() == y && b.getZ() == z;
        }

        public boolean contains(Location l) {
            return l.getWorld().getName().equals(world)
                    && l.distanceSquared(new Location(l.getWorld(), x + 0.5, y, z + 0.5)) <= (double) radius * radius;
        }
    }

    private final Database db;
    private final Supplier<HeartholdConfig> config;
    private final Logger log;
    private final NamespacedKey mobKey;
    private final Map<String, Site> sites = new LinkedHashMap<>();

    public EventService(Plugin plugin, Database db, Supplier<HeartholdConfig> config) {
        this.db = db;
        this.config = config;
        this.log = plugin.getLogger();
        this.mobKey = new NamespacedKey(plugin, "event_mob");
    }

    private EventConfig cfg() {
        return config.get().events();
    }

    public void loadAll() {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT name, type, world, x, y, z, radius, state, next_at FROM event_sites");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Site s = new Site(rs.getString(1), Type.valueOf(rs.getString(2)), rs.getString(3), rs.getInt(4),
                        rs.getInt(5), rs.getInt(6), rs.getInt(7), State.valueOf(rs.getString(8)), rs.getLong(9));
                // Waves and open chests do not survive a restart: return to an idle state.
                if (s.state == State.ACTIVE) {
                    s.state = State.READY;
                } else if (s.state == State.OPEN) {
                    s.state = State.WAITING;
                    s.nextAt = System.currentTimeMillis() + (long) (cfg().chestIntervalHours() * 3_600_000L);
                }
                sites.put(s.name.toLowerCase(Locale.ROOT), s);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load event sites", e);
        }
    }

    public Collection<Site> all() {
        return sites.values();
    }

    public Optional<Site> byName(String name) {
        return Optional.ofNullable(sites.get(name.toLowerCase(Locale.ROOT)));
    }

    public Optional<Site> coreAt(Block b) {
        return sites.values().stream().filter(s -> s.isCore(b)).findFirst();
    }

    public Optional<Site> siteAt(Location l) {
        return sites.values().stream().filter(s -> s.contains(l)).findFirst();
    }

    public boolean isEventMob(Entity e) {
        return e.getPersistentDataContainer().has(mobKey, PersistentDataType.STRING);
    }

    public Site add(String name, Type type, Block core, int radius) {
        long now = System.currentTimeMillis();
        State state = type == Type.WAVE ? State.READY : State.WAITING;
        long next = type == Type.WAVE ? 0 : now + (long) (cfg().chestIntervalHours() * 3_600_000L);
        Site s = new Site(name, type, core.getWorld().getName(), core.getX(), core.getY(), core.getZ(), radius, state, next);
        sites.put(name.toLowerCase(Locale.ROOT), s);
        save(s);
        return s;
    }

    public void remove(Site s) {
        endWaves(s);
        removeTrader(s);
        sites.remove(s.name.toLowerCase(Locale.ROOT));
        try (PreparedStatement ps = db.connection().prepareStatement("DELETE FROM event_sites WHERE name = ?")) {
            ps.setString(1, s.name);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to delete event site", e);
        }
    }

    // --- Waves ---

    public String startWaves(Site s, Player by) {
        if (s.type != Type.WAVE) {
            return Lang.tr("event.waves.not-wave");
        }
        if (s.state == State.ACTIVE) {
            return Lang.tr("event.waves.already-running");
        }
        if (s.state == State.COOLDOWN) {
            return Lang.tr("event.waves.cooldown");
        }
        if (cfg().waves().isEmpty()) {
            return Lang.tr("event.waves.not-configured");
        }
        s.state = State.ACTIVE;
        s.wave = 0;
        s.lastPlayerSeenAt = System.currentTimeMillis();
        save(s);
        Bukkit.broadcast(Component.text(by == null
                ? Lang.tr("event.waves.started", s.name, s.coords())
                : Lang.tr("event.waves.started-by", s.name, by.getName(), s.coords()), NamedTextColor.GOLD).append(compassButton(s)));
        spawnWave(s);
        return null;
    }

    private void spawnWave(Site s) {
        Optional<Location> core = s.core();
        if (core.isEmpty()) {
            fail(s, Lang.tr("event.waves.fail.world-not-loaded"));
            return;
        }
        Map<EntityType, Integer> wave = cfg().waves().get(s.wave);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int spread = Math.max(2, Math.min(8, s.radius / 2));
        s.mobs.clear();
        wave.forEach((type, count) -> {
            for (int i = 0; i < count; i++) {
                Location at = core.get().clone().add(r.nextInt(-spread, spread + 1) + 0.5, 1, r.nextInt(-spread, spread + 1) + 0.5);
                Entity e = at.getWorld().spawnEntity(at, type);
                e.getPersistentDataContainer().set(mobKey, PersistentDataType.STRING, s.name);
                if (e instanceof LivingEntity le) {
                    le.setRemoveWhenFarAway(false);
                }
                s.mobs.add(e.getUniqueId());
            }
        });
        s.waveStartedAt = System.currentTimeMillis();
        nearby(s, Component.text(Lang.tr("event.waves.wave", s.wave + 1, cfg().waves().size()), NamedTextColor.RED));
    }

    private void tickWaves(Site s, long now) {
        s.mobs.removeIf(id -> {
            Entity e = Bukkit.getEntity(id);
            return e == null || e.isDead() || !e.isValid();
        });
        boolean players = s.core().map(c -> c.getWorld().getPlayers().stream()
                .anyMatch(p -> !p.isDead() && s.contains(p.getLocation()))).orElse(false);
        if (players) {
            s.lastPlayerSeenAt = now;
        } else if (now - s.lastPlayerSeenAt > cfg().waveAbandonSeconds() * 1000L) {
            fail(s, Lang.tr("event.waves.fail.abandoned"));
            return;
        }
        if (!s.mobs.isEmpty()) {
            if (now - s.waveStartedAt > cfg().waveTimeoutSeconds() * 1000L) {
                fail(s, Lang.tr("event.waves.fail.timeout"));
            }
            return;
        }
        s.wave++;
        if (s.wave < cfg().waves().size()) {
            spawnWave(s);
            return;
        }
        // All waves cleared: reward at the core.
        s.core().ifPresent(c -> roll(cfg().waveLoot())
                .forEach(item -> c.getWorld().dropItemNaturally(c.clone().add(0, 1, 0), item)));
        Bukkit.broadcast(Component.text(Lang.tr("event.waves.completed", s.name), NamedTextColor.GREEN));
        cooldown(s);
    }

    private void fail(Site s, String why) {
        endWaves(s);
        Bukkit.broadcast(Component.text(Lang.tr("event.waves.failed", s.name, why), NamedTextColor.GRAY));
        cooldown(s);
    }

    private void endWaves(Site s) {
        for (UUID id : s.mobs) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.remove();
            }
        }
        s.mobs.clear();
    }

    private void cooldown(Site s) {
        s.state = State.COOLDOWN;
        s.nextAt = System.currentTimeMillis() + (long) (cfg().waveCooldownHours() * 3_600_000L);
        save(s);
    }

    // --- Chests ---

    /** Chat button " [Set compass]": points the player's compass at the event core. */
    public static Component compassButton(Site s) {
        return Component.text(Lang.tr("event.compass.button"), NamedTextColor.AQUA)
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        Component.text(Lang.tr("event.compass.hover", s.name()))))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/event compass " + s.name()));
    }

    /** Announce the chests now (admin); afterwards they follow the timer. */
    public void announce(Site s) {
        s.state = State.ANNOUNCED;
        s.nextAt = System.currentTimeMillis() + cfg().chestAnnounceMinutes() * 60_000L;
        save(s);
        Bukkit.broadcast(Component.text(Lang.tr("event.chests.announced", s.name, s.coords(), cfg().chestAnnounceMinutes()),
                NamedTextColor.GOLD).append(compassButton(s)));
    }

    private void open(Site s) {
        Optional<Location> core = s.core();
        if (core.isEmpty()) {
            return;
        }
        World w = core.get().getWorld();
        List<Container> chests = containers(s, w);
        if (chests.isEmpty()) {
            Block b = core.get().getBlock().getRelative(0, 1, 0);
            b.setType(Material.CHEST);
            if (b.getState(false) instanceof Container c) {
                chests.add(c);
            }
        }
        for (Container c : chests) {
            Inventory inv = c.getInventory();
            inv.clear();
            ThreadLocalRandom r = ThreadLocalRandom.current();
            for (ItemStack item : roll(cfg().chestLoot())) {
                inv.setItem(r.nextInt(inv.getSize()), item);
            }
        }
        s.state = State.OPEN;
        s.nextAt = System.currentTimeMillis() + cfg().chestOpenMinutes() * 60_000L;
        save(s);
        boolean trader = ThreadLocalRandom.current().nextDouble() < cfg().traderChance() && !cfg().trades().isEmpty();
        if (trader) {
            spawnTrader(s, core.get());
        }
        Bukkit.broadcast(Component.text(trader
                ? Lang.tr("event.chests.opened-trader", s.name, s.coords())
                : Lang.tr("event.chests.opened", s.name, s.coords()), NamedTextColor.GOLD).append(compassButton(s)));
    }

    private void close(Site s) {
        removeTrader(s);
        s.state = State.WAITING;
        s.nextAt = System.currentTimeMillis() + (long) (cfg().chestIntervalHours() * 3_600_000L);
        save(s);
        Bukkit.broadcast(Component.text(Lang.tr("event.chests.closed", s.name), NamedTextColor.GRAY));
    }

    /** Event chests within the radius (from block entities of loaded chunks). */
    private List<Container> containers(Site s, World w) {
        List<Container> result = new ArrayList<>();
        for (int cx = (s.x - s.radius) >> 4; cx <= (s.x + s.radius) >> 4; cx++) {
            for (int cz = (s.z - s.radius) >> 4; cz <= (s.z + s.radius) >> 4; cz++) {
                for (BlockState st : w.getChunkAt(cx, cz).getTileEntities()) {
                    if (st instanceof Container c && s.contains(st.getLocation().add(0.5, 0, 0.5))) {
                        result.add(c);
                    }
                }
            }
        }
        return result;
    }

    // --- Trader ---

    private void spawnTrader(Site s, Location core) {
        Villager v = core.getWorld().spawn(core.clone().add(1.5, 1, 0), Villager.class, villager -> {
            villager.customName(Component.text(Lang.tr("event.trader.name"), NamedTextColor.GOLD));
            villager.setCustomNameVisible(true);
            villager.setInvulnerable(true);
            villager.setAI(false);
            villager.setPersistent(false);
            villager.setProfession(Villager.Profession.CLERIC);
            villager.setVillagerLevel(5);
            List<MerchantRecipe> recipes = new ArrayList<>();
            for (EventConfig.Trade t : cfg().trades()) {
                MerchantRecipe recipe = new MerchantRecipe(new ItemStack(t.sell().item(), t.sell().amount()), t.uses());
                t.buy().forEach(b -> recipe.addIngredient(new ItemStack(b.item(), b.amount())));
                recipe.setExperienceReward(false);
                recipes.add(recipe);
            }
            villager.setRecipes(recipes);
            villager.getPersistentDataContainer().set(mobKey, PersistentDataType.STRING, s.name);
        });
        s.trader = v.getUniqueId();
        s.traderUntil = System.currentTimeMillis() + cfg().traderMinutes() * 60_000L;
    }

    private void removeTrader(Site s) {
        if (s.trader != null) {
            Entity e = Bukkit.getEntity(s.trader);
            if (e != null) {
                e.remove();
            }
            s.trader = null;
        }
    }

    public boolean isTrader(Entity e) {
        return sites.values().stream().anyMatch(s -> e.getUniqueId().equals(s.trader));
    }

    // --- Main loop ---

    /** Once per second. */
    public void tick() {
        long now = System.currentTimeMillis();
        for (Site s : List.copyOf(sites.values())) {
            switch (s.state) {
                case ACTIVE -> tickWaves(s, now);
                case COOLDOWN -> {
                    if (now >= s.nextAt) {
                        s.state = State.READY;
                        save(s);
                        Bukkit.broadcast(Component.text(Lang.tr("event.waves.ready-again", s.name, s.coords()),
                                NamedTextColor.GOLD).append(compassButton(s)));
                    }
                }
                case WAITING -> {
                    if (now >= s.nextAt) {
                        announce(s);
                    }
                }
                case ANNOUNCED -> {
                    if (now >= s.nextAt) {
                        open(s);
                    }
                }
                case OPEN -> {
                    if (s.trader != null && now >= s.traderUntil) {
                        removeTrader(s);
                    }
                    if (now >= s.nextAt) {
                        close(s);
                    }
                }
                default -> { }
            }
        }
    }

    public void shutdown() {
        sites.values().forEach(s -> {
            endWaves(s);
            removeTrader(s);
        });
    }

    private static List<ItemStack> roll(List<EventConfig.LootEntry> table) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<ItemStack> result = new ArrayList<>();
        for (EventConfig.LootEntry e : table) {
            if (r.nextDouble() >= e.chance()) {
                continue;
            }
            int amount = r.nextInt(e.min(), e.max() + 1);
            while (amount > 0) {
                int n = Math.min(amount, e.item().getMaxStackSize());
                result.add(new ItemStack(e.item(), n));
                amount -= n;
            }
        }
        return result;
    }

    private static void nearby(Site s, Component msg) {
        s.core().ifPresent(c -> c.getWorld().getPlayers().stream()
                .filter(p -> s.contains(p.getLocation()) || p.getLocation().distanceSquared(c) < 64 * 64)
                .forEach(p -> p.sendMessage(msg)));
    }

    private void save(Site s) {
        try (PreparedStatement ps = db.connection().prepareStatement("""
                INSERT OR REPLACE INTO event_sites (name, type, world, x, y, z, radius, state, next_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, s.name);
            ps.setString(2, s.type.name());
            ps.setString(3, s.world);
            ps.setInt(4, s.x);
            ps.setInt(5, s.y);
            ps.setInt(6, s.z);
            ps.setInt(7, s.radius);
            ps.setString(8, s.state.name());
            ps.setLong(9, s.nextAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to save event site", e);
        }
    }
}
