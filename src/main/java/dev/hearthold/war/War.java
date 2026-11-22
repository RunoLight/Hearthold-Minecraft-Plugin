package dev.hearthold.war;

import dev.hearthold.i18n.Lang;
import java.util.UUID;

/** A declared war. Active from {@code activeAt} (after preparation) until {@code endsAt} or an early end. */
public final class War {

    public enum Outcome {
        CAPTURED, PEACE, WITHDRAWN, EXPIRED, DISBANDED;

        public String title() {
            return Lang.tr("war.outcome." + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    final UUID id;
    final UUID attacker;
    final UUID defender;
    final String attackerName;
    final String defenderName;
    final long declaredAt;
    final long activeAt;
    final long endsAt;
    long endedAt;
    Outcome outcome;

    War(UUID id, UUID attacker, UUID defender, String attackerName, String defenderName,
        long declaredAt, long activeAt, long endsAt) {
        this.id = id;
        this.attacker = attacker;
        this.defender = defender;
        this.attackerName = attackerName;
        this.defenderName = defenderName;
        this.declaredAt = declaredAt;
        this.activeAt = activeAt;
        this.endsAt = endsAt;
    }

    public UUID id() {
        return id;
    }

    public UUID attacker() {
        return attacker;
    }

    public UUID defender() {
        return defender;
    }

    public String attackerName() {
        return attackerName;
    }

    public String defenderName() {
        return defenderName;
    }

    public long declaredAt() {
        return declaredAt;
    }

    public long activeAt() {
        return activeAt;
    }

    public long endsAt() {
        return endsAt;
    }

    public long endedAt() {
        return endedAt;
    }

    public Outcome outcome() {
        return outcome;
    }

    public boolean isOngoing() {
        return endedAt == 0;
    }

    /** Whether the combat phase is on now (preparation over, war not finished). */
    public boolean isActive(long now) {
        return endedAt == 0 && now >= activeAt && now < endsAt;
    }

    public boolean involves(UUID clanId) {
        return attacker.equals(clanId) || defender.equals(clanId);
    }

    public boolean between(UUID a, UUID b) {
        return (attacker.equals(a) && defender.equals(b)) || (attacker.equals(b) && defender.equals(a));
    }
}
