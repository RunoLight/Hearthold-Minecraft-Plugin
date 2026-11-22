package dev.hearthold.clan;

import dev.hearthold.i18n.Lang;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class Clan {

    /** The leader runs the clan; trusted members equip caravans and manage the treasury. */
    public enum Role {
        LEADER, TRUSTED, MEMBER;

        public String title() {
            return Lang.tr("clan.role." + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** Member tags: access on top of the role. The leader always has every access. */
    public enum Tag {
        /** Access to the base heart treasury: deposit and withdraw upkeep emeralds. */
        TREASURER,
        /** Access to resource collector barrels. */
        QUARTERMASTER,
        /** Builds and breaks across the whole clan territory (plots included), places nodes and proxies. */
        BUILDER;

        public String title() {
            return Lang.tr("clan.tag." + name().toLowerCase(java.util.Locale.ROOT));
        }

        public String description() {
            return Lang.tr("clan.tag." + name().toLowerCase(java.util.Locale.ROOT) + ".desc");
        }
    }

    /** Plot the player lives in (invited to), for the "Has a home" badge. Wired by the plugin. */
    private static java.util.function.Function<UUID, String> homeLookup = id -> null;

    public static void setHomeLookup(java.util.function.Function<UUID, String> lookup) {
        homeLookup = lookup;
    }

    /** Name of the player's home plot, or null. */
    public static String homeOf(UUID player) {
        return homeLookup.apply(player);
    }

    private final UUID id;
    private final String name;
    private final long createdAt;
    private final Map<UUID, Role> members = new LinkedHashMap<>();
    private final Map<UUID, java.util.EnumSet<Tag>> tags = new java.util.HashMap<>();

    public Clan(UUID id, String name, long createdAt) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public long createdAt() {
        return createdAt;
    }

    /** Name prefix of test dummy clans from the admin menu (persisted identifier, not translated); players cannot use it. */
    public static final String DUMMY_PREFIX = "Dummy";
    /** Prefix used by dummies created before the rebrand; still recognised. */
    private static final String LEGACY_DUMMY_PREFIX = "Манекен";

    /** Test dummy clan: no live players, accepts trade offers automatically. */
    public boolean isDummy() {
        return name.startsWith(DUMMY_PREFIX) || name.startsWith(LEGACY_DUMMY_PREFIX);
    }

    public Map<UUID, Role> members() {
        return Collections.unmodifiableMap(members);
    }

    public boolean isMember(UUID player) {
        return members.containsKey(player);
    }

    public boolean isLeader(UUID player) {
        return members.get(player) == Role.LEADER;
    }

    /** Whether the player may manage the treasury and equip caravans. */
    public boolean canTrade(UUID player) {
        Role r = members.get(player);
        return r == Role.LEADER || r == Role.TRUSTED;
    }

    /** Member tags (leadership not included). */
    public java.util.Set<Tag> tagsOf(UUID player) {
        java.util.EnumSet<Tag> t = tags.get(player);
        return t == null ? java.util.Set.of() : java.util.Collections.unmodifiableSet(t);
    }

    /** Whether the player has the tag's access; the leader always does. */
    public boolean hasAccess(UUID player, Tag tag) {
        return isLeader(player) || tagsOf(player).contains(tag);
    }

    /** "Leader · Treasurer · Quartermaster · Has a home" for UI labels. */
    public String badges(UUID player) {
        Role r = members.get(player);
        if (r == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(r == Role.MEMBER ? "" : r.title());
        for (Tag t : tagsOf(player)) {
            sb.append(sb.isEmpty() ? "" : " · ").append(t.title());
        }
        if (homeOf(player) != null) {
            sb.append(sb.isEmpty() ? "" : " · ").append(Lang.tr("clan.badge.has-home"));
        }
        return sb.toString();
    }

    void setTag(UUID player, Tag tag, boolean on) {
        if (on) {
            tags.computeIfAbsent(player, p -> java.util.EnumSet.noneOf(Tag.class)).add(tag);
        } else if (tags.containsKey(player)) {
            tags.get(player).remove(tag);
        }
    }

    void putMember(UUID player, Role role) {
        members.put(player, role);
    }

    void removeMember(UUID player) {
        members.remove(player);
        tags.remove(player);
    }
}
