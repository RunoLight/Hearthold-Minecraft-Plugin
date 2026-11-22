package dev.hearthold.trade;

import dev.hearthold.clan.Clan;
import dev.hearthold.clan.ClanException;
import dev.hearthold.clan.ClanService;
import dev.hearthold.i18n.Lang;
import dev.hearthold.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mule;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Clan stable: horses and wagons in the treasury. A horse is stored whole (serialized), handed out for a trip
 * and returned by standing on it for 10 seconds in the own base. A horse can be upgraded to a wagon:
 * a mule with a chest and the same speed.
 */
public final class Stable {

    public enum Kind {
        HORSE, WAGON;

        public String displayName() {
            return Lang.tr(this == WAGON ? "trade.stable.wagon" : "trade.stable.horse");
        }
    }

    public enum Status { HOME, OUT }

    public static final class Entry {
        final UUID id;
        final UUID clanId;
        Kind kind;
        byte[] data;
        final double speed;
        Status status;
        UUID entityId;

        Entry(UUID id, UUID clanId, Kind kind, byte[] data, double speed, Status status, UUID entityId) {
            this.id = id;
            this.clanId = clanId;
            this.kind = kind;
            this.data = data;
            this.speed = speed;
            this.status = status;
            this.entityId = entityId;
        }

        public UUID id() {
            return id;
        }

        public Kind kind() {
            return kind;
        }

        public Status status() {
            return status;
        }

        public double speed() {
            return speed;
        }
    }

    private final Database db;
    private final Logger log;
    private final NamespacedKey entryKey;
    private final NamespacedKey leftKey;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    public Stable(Plugin plugin, Database db, ClanService clans) {
        this.db = db;
        this.log = plugin.getLogger();
        this.entryKey = new NamespacedKey(plugin, "stable_entry");
        this.leftKey = new NamespacedKey(plugin, "stable_left");
        clans.onDisband(clan -> entries.values().removeIf(e -> e.clanId.equals(clan.id())));
    }

    public void loadAll() {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT id, clan_id, kind, data, speed, status, entity_id FROM stable");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String ent = rs.getString(7);
                Entry e = new Entry(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                        Kind.valueOf(rs.getString(3)), rs.getBytes(4), rs.getDouble(5),
                        Status.valueOf(rs.getString(6)), ent == null ? null : UUID.fromString(ent));
                entries.put(e.id, e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load stables", e);
        }
    }

    public List<Entry> of(UUID clanId) {
        return entries.values().stream().filter(e -> e.clanId.equals(clanId)).toList();
    }

    /** The stable entry the animal belongs to (if it was handed out from the treasury). */
    public Optional<Entry> entryOf(Entity entity) {
        String id = entity.getPersistentDataContainer().get(entryKey, PersistentDataType.STRING);
        return id == null ? Optional.empty() : Optional.ofNullable(entries.get(UUID.fromString(id)));
    }

    public UUID clanOf(Entry e) {
        return e.clanId;
    }

    public boolean hasLeftBase(Entity entity) {
        return entity.getPersistentDataContainer().getOrDefault(leftKey, PersistentDataType.BYTE, (byte) 0) == 1;
    }

    public void markLeft(Entity entity) {
        entity.getPersistentDataContainer().set(leftKey, PersistentDataType.BYTE, (byte) 1);
    }

    /** Stores the tamed, saddled horse the player is riding into the treasury. */
    public Entry add(Clan clan, Player player) throws ClanException {
        if (!(player.getVehicle() instanceof AbstractHorse horse) || !horse.isTamed()
                || horse.getInventory().getSaddle() == null) {
            throw new ClanException(Lang.tr("trade.stable.need-horse"));
        }
        Optional<Entry> previous = entryOf(horse);
        if (previous.isPresent() && previous.get().clanId.equals(clan.id())) {
            throw new ClanException(Lang.tr("trade.stable.own-horse"));
        }
        // A horse from another clan's treasury has been stolen: remove it from their records.
        previous.ifPresent(this::delete);
        Kind kind = horse instanceof Mule && ((Mule) horse).isCarryingChest() ? Kind.WAGON : Kind.HORSE;
        double speed = speedOf(horse);
        horse.getPersistentDataContainer().remove(entryKey);
        horse.getPersistentDataContainer().remove(leftKey);
        player.leaveVehicle();
        byte[] data = serialize(horse);
        Entry e = new Entry(UUID.randomUUID(), clan.id(), kind, data, speed, Status.HOME, null);
        horse.remove();
        entries.put(e.id, e);
        save(e);
        return e;
    }

    /** Hands out an animal from the treasury next to the player. */
    public Entity take(Entry e, Location at, Player owner) throws ClanException {
        if (e.status != Status.HOME) {
            throw new ClanException(Lang.tr("trade.stable.already-out"));
        }
        Entity entity;
        if (e.data != null) {
            entity = deserialize(e.data, at.getWorld());
            entity.spawnAt(at);
        } else {
            entity = at.getWorld().spawn(at, Mule.class, mule -> {
                mule.setTamed(true);
                mule.setOwner(owner);
                mule.setAdult();
                mule.setCarryingChest(true);
                mule.getInventory().setSaddle(new ItemStack(Material.SADDLE));
                var attr = mule.getAttribute(Attribute.MOVEMENT_SPEED);
                if (attr != null) {
                    attr.setBaseValue(e.speed);
                }
            });
        }
        entity.getPersistentDataContainer().set(entryKey, PersistentDataType.STRING, e.id.toString());
        entity.getPersistentDataContainer().set(leftKey, PersistentDataType.BYTE, (byte) 0);
        e.status = Status.OUT;
        e.entityId = entity.getUniqueId();
        save(e);
        return entity;
    }

    /** The animal is back in base: store it again (with everything it carries). */
    public void store(Entry e, AbstractHorse horse) {
        horse.getPersistentDataContainer().remove(entryKey);
        horse.getPersistentDataContainer().remove(leftKey);
        horse.eject();
        e.data = serialize(horse);
        e.status = Status.HOME;
        e.entityId = null;
        horse.remove();
        save(e);
    }

    /** Upgrades a horse to a wagon: from now on it is handed out as a mule with a chest and the same speed. */
    public void upgrade(Entry e) throws ClanException {
        if (e.status != Status.HOME) {
            throw new ClanException(Lang.tr("trade.stable.upgrade-home-only"));
        }
        if (e.kind == Kind.WAGON) {
            throw new ClanException(Lang.tr("trade.stable.already-wagon"));
        }
        e.kind = Kind.WAGON;
        e.data = null;
        save(e);
    }

    public void delete(Entry e) {
        entries.remove(e.id);
        try (PreparedStatement ps = db.connection().prepareStatement("DELETE FROM stable WHERE id = ?")) {
            ps.setString(1, e.id.toString());
            ps.executeUpdate();
        } catch (SQLException ex) {
            log.log(Level.WARNING, "Failed to delete stable entry", ex);
        }
    }

    public List<Entry> list(UUID clanId) {
        return new ArrayList<>(of(clanId));
    }

    // Paper only exposes entity serialization through UnsafeValues (deprecated, but there is no replacement).
    @SuppressWarnings("deprecation")
    private static byte[] serialize(Entity entity) {
        return Bukkit.getUnsafe().serializeEntity(entity);
    }

    @SuppressWarnings("deprecation")
    private static Entity deserialize(byte[] data, org.bukkit.World world) {
        return Bukkit.getUnsafe().deserializeEntity(data, world);
    }

    private static double speedOf(AbstractHorse horse) {
        var attr = horse.getAttribute(Attribute.MOVEMENT_SPEED);
        return attr == null ? 0.2 : attr.getBaseValue();
    }

    private void save(Entry e) {
        try (PreparedStatement ps = db.connection().prepareStatement("""
                INSERT OR REPLACE INTO stable (id, clan_id, kind, data, speed, status, entity_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, e.id.toString());
            ps.setString(2, e.clanId.toString());
            ps.setString(3, e.kind.name());
            ps.setBytes(4, e.data);
            ps.setDouble(5, e.speed);
            ps.setString(6, e.status.name());
            ps.setString(7, e.entityId == null ? null : e.entityId.toString());
            ps.executeUpdate();
        } catch (SQLException ex) {
            log.log(Level.WARNING, "Failed to save stable", ex);
        }
    }

    /** Whether the entity carries the PDC marker of a treasury animal. */
    public boolean isFromStable(Entity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        return pdc.has(entryKey, PersistentDataType.STRING);
    }
}
