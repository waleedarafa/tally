package com.tally.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.shared.Money;
import com.tally.support.AbstractProviderIntegrationTest;
import com.tally.support.FakeProvider;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** Brief section 5: circuit breaker, rate limit, and calls the provider refused. Own context. */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms",
        "tally.payment.breaker.minimum-calls=3",
        "tally.payment.breaker.sliding-window=5",
        "tally.payment.breaker.open-for=30s",
        "tally.payment.instances=1",
        "tally.payment.provider.rate-limit-per-second=5",
        "tally.test.context=provider-isolation"
})
class ProviderIsolationIT extends AbstractProviderIntegrationTest {

    @Autowired
    PaymentApi payments;

    @Autowired
    MeterRegistry meters;

    @Autowired
    JdbcTemplate jdbc;

    double breakerState() {
        return meters.get("tally.payment.breaker.state").gauge().value();
    }

    List<String> collect(int count) {
        List<String> references = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String reference = "ISO-" + UUID.randomUUID();
            payments.requestCollection(reference, UUID.randomUUID(), Money.of(999, "USD"), FakeProvider.token());
            references.add(reference);
        }
        return references;
    }

    boolean all(List<String> references, AttemptState state) {
        return references.stream().allMatch(r -> payments.status(r).orElseThrow().state() == state);
    }

    int requests(List<String> references) {
        return references.stream().mapToInt(FakeProvider::requestCount).sum();
    }

    @Test
    void anOutageOpensTheBreakerWhichStopsClaimsUntilTheProviderRecovers() {
        FakeProvider.mode("OUTAGE");
        List<String> during = collect(4);

        await().atMost(Duration.ofSeconds(10)).until(() -> breakerState() == 1);
        // A 503 means nothing was charged: each 503 makes its attempt a technical failure, not UNKNOWN, and
        // its retry a new REQUESTED attempt (ADR-013). An attempt the open breaker held back was never sent:
        // it is still REQUESTED. So every reference's latest attempt is REQUESTED, with one technical
        // failure per 503 the provider answered.
        await().atMost(Duration.ofSeconds(10)).until(() -> all(during, AttemptState.REQUESTED));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.payment_attempt"
                + " WHERE state = 'TECHNICAL_FAILURE' AND reference = ANY (?::text[])", Integer.class,
                (Object) during.toArray(String[]::new))).isEqualTo(requests(during)).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.payment_attempt"
                + " WHERE state = 'UNKNOWN' AND reference = ANY (?::text[])", Integer.class,
                (Object) during.toArray(String[]::new))).isZero();

        // While the breaker is open, new work is not even claimed.
        List<String> whileOpen = collect(3);
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).until(() -> requests(whileOpen) == 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.collection_queue"
                + " WHERE status = 'READY' AND attempts = 0 AND queue_key IN (SELECT id::text FROM payment.payment_attempt"
                + " WHERE reference = ANY (?::text[]))", Integer.class, (Object) whileOpen.toArray(String[]::new)))
                .isEqualTo(3);

        // The provider recovers; once the breaker's wait is over, trial calls close it again.
        FakeProvider.mode("NORMAL");
        clock.advance(Duration.ofSeconds(31));
        List<String> all = new ArrayList<>(during);
        all.addAll(whileOpen);
        // Half-open allows a few trial calls; the rest are held back a second, so time must pass.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).until(() -> {
            clock.advance(Duration.ofSeconds(1));
            return all(all, AttemptState.SUCCEEDED);
        });
        assertThat(breakerState()).isZero();
        all.forEach(r -> assertThat(FakeProvider.chargeCount(r)).isEqualTo(1));
    }

    @Test
    void aRateLimitedCallIsRetriedAsANewAttemptAndChargedOnceLater() {
        // The provider allows 2 calls a second; this instance allows itself 5.
        FakeProvider.mode("RATE_LIMIT(2)");
        List<String> references = collect(10);

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(300)).until(() -> {
            clock.advance(Duration.ofSeconds(1)); // Lets retries and held-back attempts fall due.
            return all(references, AttemptState.SUCCEEDED);
        });

        assertThat(requests(references)).as("some calls were refused with 429").isGreaterThan(10);
        references.forEach(r -> assertThat(FakeProvider.chargeCount(r)).isEqualTo(1));
        // A 429 is not a provider failure: the breaker stays closed.
        assertThat(breakerState()).isZero();
    }

    @Test
    void theLocalRateLimitKeepsCallsUnderTheProvidersLimit() {
        // Two of this instance's 5-per-second windows can overlap one provider second: at most 10.
        FakeProvider.mode("RATE_LIMIT(10)");
        List<String> references = collect(20);

        // Workers beyond the local limit are held back (no provider call) and come back a second later.
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(300)).until(() -> {
            clock.advance(Duration.ofSeconds(1));
            return all(references, AttemptState.SUCCEEDED);
        });

        assertThat(requests(references)).as("no call reached the provider only to be refused with 429").isEqualTo(20);
    }
}
