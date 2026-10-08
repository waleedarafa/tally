package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.support.ChaosDatabase;
import com.tally.support.FakeProvider;
import com.tally.support.TallyProcess;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AT-10. Throughput, scaled (D6). Pass when: with the provider limited to 100 calls a second, there is
 * no 429 storm and every first attempt is made. 2,000 renewals in CI ({@code -Dtally.at10.renewals};
 * 10,000 nightly).
 *
 * <p>Two real Tally processes on production settings (100 calls a second; Tally aims at 95, shared by 2 instances; 15
 * payment workers each, 1 s poll interval), real clock, fresh database, every renewal due at once.
 * A "storm" is taken to mean more than 1% of calls refused with 429.
 */
class AT10ThroughputIT {

    static final int RENEWALS = Integer.getInteger("tally.at10.renewals", 2_000);
    static final double STORM = 0.01;

    final List<TallyProcess> instances = new ArrayList<>();

    @AfterEach
    void stop() {
        instances.forEach(TallyProcess::close);
        FakeProvider.mode("NORMAL");
    }

    static long count(JdbcTemplate jdbc, String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void renewalsAreChargedAtTheProvidersRateWithoutA429Storm() {
        ChaosDatabase db = ChaosDatabase.create("at10");
        JdbcTemplate jdbc = db.jdbc();
        Map<String, String> settings = Map.of("tally.payment.provider.base-url", FakeProvider.baseUrl());
        instances.add(TallyProcess.start("at10-a", db.url(), settings));
        instances.add(TallyProcess.start("at10-b", db.url(), settings));

        FakeProvider.mode("RATE_LIMIT(100)");
        Map<String, Long> before = FakeProvider.stats();
        long started = System.nanoTime();
        db.seedDueRenewals(RENEWALS, LocalDate.now(ZoneOffset.UTC).minusDays(1), FakeProvider.token());

        await().atMost(Duration.ofMinutes(15)).pollInterval(Duration.ofSeconds(1))
                .until(() -> count(jdbc, "SELECT count(*) FROM payment.payment_attempt WHERE state = 'SUCCEEDED'") == RENEWALS);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        Map<String, Long> after = FakeProvider.stats();

        long requests = after.get("requests") - before.get("requests");
        long rateLimited = after.get("rateLimited") - before.get("rateLimited");
        long charged = after.get("charged") - before.get("charged");
        double share = (double) rateLimited / requests;
        System.out.printf("AT-10: %d renewals charged in %d s (%.1f charges/s); provider requests %d, 429s %d (%.2f%%),"
                        + " charges %d%n", RENEWALS, elapsed.toSeconds(), RENEWALS / (elapsed.toMillis() / 1000.0),
                requests, rateLimited, share * 100, charged);

        // Every first attempt made, each charged once.
        assertThat(charged).isEqualTo(RENEWALS);
        assertThat(count(jdbc, "SELECT count(*) FROM payment.payment_attempt")).isEqualTo(RENEWALS);
        assertThat(count(jdbc, "SELECT count(*) FROM payment.payment_attempt WHERE state IN ('FAILED', 'UNKNOWN')")).isZero();
        // No 429 storm.
        assertThat(share).as("share of calls refused with 429").isLessThanOrEqualTo(STORM);
    }
}
