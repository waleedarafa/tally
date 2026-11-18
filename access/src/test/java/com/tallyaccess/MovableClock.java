package com.tallyaccess;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test only: a clock a test moves, injected in place of the system clock (CLAUDE.md rule 4), so AT-30 can move past a
 * token's end. It starts at the system time: the log's records and the key set's fetch carry real times.
 */
public final class MovableClock extends Clock {

    private final Clock system = Clock.systemUTC();
    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

    public void advance(Duration duration) {
        offset.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public Instant instant() {
        return system.instant().plus(offset.get());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    /** Registers {@code clock} as the {@code Clock} of an instance a test starts. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {

        static MovableClock next;

        @Bean
        @Primary
        Clock movableClock() {
            return next;
        }
    }
}
