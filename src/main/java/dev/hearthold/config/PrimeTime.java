package dev.hearthold.config;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

/**
 * Prime-time schedule. A window may cross midnight (22:00-02:00): its part after midnight
 * belongs to the day the window started on.
 */
public record PrimeTime(ZoneId zone, Set<DayOfWeek> days, List<Window> windows) {

    public record Window(LocalTime from, LocalTime to) {

        boolean crossesMidnight() {
            return !to.isAfter(from);
        }

        public static Window parse(String spec) {
            String[] parts = spec.trim().split("-");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Expected HH:MM-HH:MM, got: " + spec);
            }
            return new Window(LocalTime.parse(parts[0].trim()), LocalTime.parse(parts[1].trim()));
        }

        @Override
        public String toString() {
            return from + "-" + to;
        }
    }

    public boolean isActive(ZonedDateTime moment) {
        ZonedDateTime local = moment.withZoneSameInstant(zone);
        LocalTime time = local.toLocalTime();
        DayOfWeek today = local.getDayOfWeek();
        DayOfWeek yesterday = today.minus(1);
        for (Window w : windows) {
            if (!w.crossesMidnight()) {
                if (days.contains(today) && !time.isBefore(w.from()) && time.isBefore(w.to())) {
                    return true;
                }
            } else {
                if (days.contains(today) && !time.isBefore(w.from())) {
                    return true;
                }
                if (days.contains(yesterday) && time.isBefore(w.to())) {
                    return true;
                }
            }
        }
        return false;
    }
}
