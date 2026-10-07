package com.tally.platform;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock tests can set and advance (D4). Thread-safe, so pollers and tests can share it. */
public final class TestClock extends Clock {

    /** A fixed, arbitrary starting point, so tests never depend on the real date. */
    public static final Instant DEFAULT_START = Instant.parse("2026-01-15T10:00:00Z");

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    public TestClock() {
        this(DEFAULT_START, ZoneOffset.UTC);
    }

    private TestClock(Instant start, ZoneId zone) {
        this.now = new AtomicReference<>(start);
        this.zone = zone;
    }

    private TestClock(AtomicReference<Instant> shared, ZoneId zone) {
        this.now = shared;
        this.zone = zone;
    }

    public void set(Instant instant) {
        now.set(instant);
    }

    public Instant advance(Duration duration) {
        return now.updateAndGet(i -> i.plus(duration));
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    /** A view in another zone that shares this clock's time. */
    @Override
    public Clock withZone(ZoneId zone) {
        return new TestClock(now, zone);
    }
}
