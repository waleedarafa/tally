package com.tally.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.shared.Money;
import com.tally.support.AbstractProviderIntegrationTest;
import com.tally.support.FakeProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** ADR-018: unknown outcomes are reconciled, never retried; recent failures are re-checked. Own context. */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms",
        "tally.test.context=reconciliation"
})
class ReconciliationIT extends AbstractProviderIntegrationTest {

    @Autowired
    PaymentApi payments;

    @Autowired
    MeterRegistry meters;

    @Autowired
    JdbcTemplate jdbc;

    /**
     * The job's row is shared by every test context, and earlier contexts may have rescheduled it to
     * their own clock (years ahead, after AT-3). Bring it due at this context's clock.
     */
    @BeforeEach
    void reconciliationDueNow() {
        jdbc.update("UPDATE payment.jobs SET status = 'READY', claim_token = NULL, due_at = ?"
                + " WHERE queue_key = 'payment-reconciliation'", clock.instant().atOffset(ZoneOffset.UTC));
    }

    double corrected(String from, String to) {
        Counter counter = meters.find("tally.payment.outcome_corrected").tags("from", from, "to", to).counter();
        return counter == null ? 0 : counter.count();
    }

    String collect() {
        String reference = "REC-" + UUID.randomUUID();
        payments.requestCollection(reference, UUID.randomUUID(), Money.of(999, "USD"), FakeProvider.token());
        return reference;
    }

    AttemptState awaitState(String reference, AttemptState state, Duration atMost) {
        await().atMost(atMost).until(() -> payments.status(reference).map(s -> s.state() == state).orElse(false));
        return state;
    }

    int checks(String reference) {
        return jdbc.queryForObject("SELECT reconcile_checks FROM payment.payment_attempt WHERE reference = ?",
                Integer.class, reference);
    }

    Instant reconcileAt(String reference) {
        OffsetDateTime at = jdbc.queryForObject("SELECT reconcile_at FROM payment.payment_attempt WHERE reference = ?",
                OffsetDateTime.class, reference);
        return at == null ? null : at.toInstant();
    }

    @Test
    void aTimeoutAfterChargeIsUnknownNeverRetriedAndReconciledToSucceeded() {
        double before = corrected("UNKNOWN", "SUCCEEDED");
        FakeProvider.mode("TIMEOUT_AFTER_CHARGE");
        String reference = collect();

        awaitState(reference, AttemptState.UNKNOWN, Duration.ofSeconds(15));
        FakeProvider.mode("NORMAL");
        // Not retried: still one request after several poll cycles.
        await().during(Duration.ofSeconds(1)).until(() -> FakeProvider.requestCount(reference) == 1);

        clock.advance(Duration.ofMinutes(5));
        awaitState(reference, AttemptState.SUCCEEDED, Duration.ofSeconds(10));
        assertThat(FakeProvider.chargeCount(reference)).isEqualTo(1);
        assertThat(FakeProvider.requestCount(reference)).isEqualTo(1);
        assertThat(corrected("UNKNOWN", "SUCCEEDED")).isEqualTo(before + 1);
        assertThat(reconcileAt(reference)).isNull();
    }

    @Test
    void aChargeReportedAsDeclinedIsCorrectedToSucceeded() {
        double before = corrected("FAILED", "SUCCEEDED");
        FakeProvider.mode("LIE");
        String reference = collect();

        awaitState(reference, AttemptState.FAILED, Duration.ofSeconds(10));
        FakeProvider.mode("NORMAL");

        clock.advance(Duration.ofMinutes(5));
        awaitState(reference, AttemptState.SUCCEEDED, Duration.ofSeconds(10));
        assertThat(corrected("FAILED", "SUCCEEDED")).isEqualTo(before + 1);
        assertThat(FakeProvider.chargeCount(reference)).isEqualTo(1);
    }

    @Test
    void anUnknownAttemptTheProviderNeverChargedResolvesAsNotCharged() {
        double before = corrected("UNKNOWN", "FAILED");
        String reference = "REC-" + UUID.randomUUID();
        var now = clock.instant().atOffset(ZoneOffset.UTC);
        jdbc.update("INSERT INTO payment.payment_attempt (id, reference, account_id, amount_minor, currency,"
                + " payment_token, state, created_at, updated_at, reconcile_at)"
                + " VALUES (?, ?, ?, 999, 'USD', 'tok', 'UNKNOWN', ?, ?, ?)",
                UUID.randomUUID(), reference, UUID.randomUUID(), now, now, now.plusMinutes(5));

        clock.advance(Duration.ofMinutes(5));
        awaitState(reference, AttemptState.FAILED, Duration.ofSeconds(10));
        assertThat(payments.status(reference).orElseThrow().failureReason()).contains(FailureReason.SOFT);
        assertThat(corrected("UNKNOWN", "FAILED")).isEqualTo(before + 1);
    }

    @Test
    void aGenuineDeclineIsRecheckedWithBackoffForFortyEightHoursOnly() {
        double before = corrected("FAILED", "SUCCEEDED");
        FakeProvider.mode("DECLINE_HARD(100)");
        String reference = collect();
        awaitState(reference, AttemptState.FAILED, Duration.ofSeconds(10));
        FakeProvider.mode("NORMAL");
        Instant failedAt = clock.instant();
        assertThat(reconcileAt(reference)).isEqualTo(failedAt.plus(Duration.ofMinutes(5)));

        // 5 minutes, then 1 hour, then daily.
        clock.advance(Duration.ofMinutes(5));
        await().atMost(Duration.ofSeconds(10)).until(() -> checks(reference) == 1);
        assertThat(reconcileAt(reference)).isEqualTo(clock.instant().plus(Duration.ofHours(1)));

        clock.advance(Duration.ofHours(1));
        await().atMost(Duration.ofSeconds(10)).until(() -> checks(reference) == 2);
        assertThat(reconcileAt(reference)).isEqualTo(clock.instant().plus(Duration.ofDays(1)));

        // The next daily check would fall beyond 48 hours after the failure: checks stop.
        clock.advance(Duration.ofDays(1));
        await().atMost(Duration.ofSeconds(10)).until(() -> checks(reference) == 3);
        assertThat(reconcileAt(reference)).isNull();

        assertThat(payments.status(reference).orElseThrow().state()).isEqualTo(AttemptState.FAILED);
        assertThat(corrected("FAILED", "SUCCEEDED")).isEqualTo(before);
        assertThat(FakeProvider.chargeCount(reference)).isEqualTo(1);
    }
}
