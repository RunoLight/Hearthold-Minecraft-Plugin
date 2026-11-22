package dev.hearthold.trade;

import dev.hearthold.i18n.Lang;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Trade route between two clans: every {@code periodDays} days each side must deliver its cargo lines.
 * Being overdue does not break the route: the wronged side decides whether to forgive or terminate.
 */
public final class TradeRoute {

    public record Line(UUID fromClan, Material material, int amount) { }

    public enum Status { ACTIVE, TERMINATED }

    public enum RowStatus {
        DONE("trade.row.done"),
        FORGIVEN("trade.row.forgiven"),
        IN_PROGRESS("trade.row.in-progress"),
        OVERDUE("trade.row.overdue"),
        FUTURE("trade.row.future");

        private final String key;

        RowStatus(String key) {
            this.key = key;
        }

        public String title() {
            return Lang.tr(key);
        }
    }

    record DeliveryKey(int period, UUID fromClan, Material material) { }

    record ForgiveKey(int period, UUID fromClan) { }

    final UUID id;
    final UUID clanA;
    final UUID clanB;
    final int periodDays;
    final long startAt;
    Status status = Status.ACTIVE;
    UUID terminatedBy;
    String reason;
    long terminatedAt;
    final List<Line> lines = new ArrayList<>();
    final Map<DeliveryKey, Integer> delivered = new HashMap<>();
    final Set<ForgiveKey> forgiven = new HashSet<>();

    TradeRoute(UUID id, UUID clanA, UUID clanB, int periodDays, long startAt) {
        this.id = id;
        this.clanA = clanA;
        this.clanB = clanB;
        this.periodDays = periodDays;
        this.startAt = startAt;
    }

    public UUID id() {
        return id;
    }

    /** Short id for commands. */
    public String shortId() {
        return id.toString().substring(0, 4);
    }

    public UUID clanA() {
        return clanA;
    }

    public UUID clanB() {
        return clanB;
    }

    public int periodDays() {
        return periodDays;
    }

    public long startAt() {
        return startAt;
    }

    public Status status() {
        return status;
    }

    public String reason() {
        return reason;
    }

    public List<Line> lines() {
        return List.copyOf(lines);
    }

    public boolean involves(UUID clanId) {
        return clanA.equals(clanId) || clanB.equals(clanId);
    }

    public UUID partnerOf(UUID clanId) {
        return clanA.equals(clanId) ? clanB : clanA;
    }

    public long periodMs() {
        return periodDays * 86_400_000L;
    }

    public int periodAt(long time) {
        return (int) Math.floorDiv(time - startAt, periodMs());
    }

    public int currentPeriod() {
        return periodAt(System.currentTimeMillis());
    }

    public long periodStart(int period) {
        return startAt + period * periodMs();
    }

    public int delivered(int period, UUID fromClan, Material m) {
        return delivered.getOrDefault(new DeliveryKey(period, fromClan, m), 0);
    }

    public boolean isForgiven(int period, UUID fromClan) {
        return forgiven.contains(new ForgiveKey(period, fromClan));
    }

    public RowStatus rowStatus(int period, Line line) {
        int current = currentPeriod();
        if (period > current) {
            return RowStatus.FUTURE;
        }
        if (delivered(period, line.fromClan(), line.material()) >= line.amount()) {
            return RowStatus.DONE;
        }
        if (isForgiven(period, line.fromClan())) {
            return RowStatus.FORGIVEN;
        }
        return period == current ? RowStatus.IN_PROGRESS : RowStatus.OVERDUE;
    }

    /** Whether the clan has at least one overdue (unforgiven) line. */
    public boolean hasOverdue(UUID fromClan) {
        int current = currentPeriod();
        for (int p = 0; p < current; p++) {
            for (Line l : lines) {
                if (l.fromClan().equals(fromClan) && rowStatus(p, l) == RowStatus.OVERDUE) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * How much the clan still owes: per material, the undelivered sum over all open
     * (overdue and current) periods.
     */
    public Map<Material, Integer> remaining(UUID fromClan) {
        Map<Material, Integer> result = new LinkedHashMap<>();
        if (status != Status.ACTIVE) {
            return result;
        }
        int current = currentPeriod();
        for (int p = 0; p <= current; p++) {
            for (Line l : lines) {
                if (!l.fromClan().equals(fromClan)) {
                    continue;
                }
                RowStatus s = rowStatus(p, l);
                if (s == RowStatus.OVERDUE || s == RowStatus.IN_PROGRESS) {
                    result.merge(l.material(), l.amount() - delivered(p, fromClan, l.material()), Integer::sum);
                }
            }
        }
        return result;
    }
}
