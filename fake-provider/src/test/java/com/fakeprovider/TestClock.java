package com.fakeprovider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A clock that moves only when a test moves it, so a rate-limit window cannot roll over between two
 * requests of one test. A test that counts against the limit first opens a window of its own.
 */
class TestClock extends Clock {

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {

        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }
    }

    private volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");

    /** Starts a new one-second rate-limit window. */
    void nextSecond() {
        now = now.plus(Duration.ofSeconds(1));
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException();
    }
}
