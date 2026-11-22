package dev.hearthold.health;

import dev.hearthold.config.HeartholdConfig;

import java.time.ZonedDateTime;
import java.util.function.Supplier;

/** Whether prime time is active now. An admin can temporarily force it on or off. */
public final class PrimeTimeClock {

    private final Supplier<HeartholdConfig> config;
    /** null means follow the schedule. */
    private Boolean forced;

    public PrimeTimeClock(Supplier<HeartholdConfig> config) {
        this.config = config;
    }

    public boolean isActive() {
        return forced != null ? forced : config.get().primeTime().isActive(ZonedDateTime.now());
    }

    public void force(Boolean value) {
        this.forced = value;
    }

    public Boolean forced() {
        return forced;
    }
}
