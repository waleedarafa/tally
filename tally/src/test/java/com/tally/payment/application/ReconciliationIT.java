package com.tally.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.payment.AttemptState;
import com.tally.payment.domain.PaymentAttempts;
import com.tally.shared.Money;
import com.tally.support.AbstractReconcilingTest;
import com.tally.support.FakeProvider;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * FR-018: unknown outcomes are reconciled, never retried; recent failures are re-checked.
 *
 * <p>The scheduled reconciliation job is stopped for these tests, in every test context at once: its row in
 * {@code payment.jobs} is shared, and is set due in the year 9999. Each check is then a direct call, so the
 * attempt's checks, its next check time and this context's correction counters change only when the test
 * says so (PR #23 review).
 */
class ReconciliationIT extends AbstractReconcilingTest {

    @Autowired
    Reconciliation reconciliation;

    @Autowired
    PaymentAttempts attempts;

    /** Runs after the base class makes the job due: waits for any run in progress, then parks the job. */
    @BeforeEach
    void stopTheScheduledJob() {
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.update("UPDATE payment.jobs"
                + " SET due_at = TIMESTAMPTZ '9999-01-01 00:00:00Z' WHERE queue_key = 'payment-reconciliation'"
                + " AND status = 'READY'") == 1);
    }

    String collect() {
        String reference = "REC-" + UUID.randomUUID();
        payments.requestCollection(reference, UUID.randomUUID(), Money.of(999, "USD"), FakeProvider.token());
        return reference;
    }

    void awaitState(String reference, AttemptState state, Duration atMost) {
        await().atMost(atMost).until(() -> payments.status(reference).map(s -> s.state() == state).orElse(false));
    }

    /** One reconciliation check of the reference's attempt, now. */
    void check(String reference) {
        reconciliation.check(attempts.latestFor(reference).orElseThrow());
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
        check(reference);

        assertThat(payments.status(reference).orElseThrow().state()).isEqualTo(AttemptState.SUCCEEDED);
        assertThat(FakeProvider.chargeCount(reference)).isEqualTo(1);
        assertThat(FakeProvider.requestCount(reference)).isEqualTo(1);
        assertThat(corrected("UNKNOWN", "SUCCEEDED")).isEqualTo(before + 1);
        assertThat(reconcileAt(reference)).isNull();
    }

    @Test
    void aChargeReportedAsDeclinedIsCorrectedToSucceeded() {
        double before = corrected("DECLINED", "SUCCEEDED");
        FakeProvider.mode("LIE");
        String reference = collect();

        awaitState(reference, AttemptState.DECLINED, Duration.ofSeconds(10));
        FakeProvider.mode("NORMAL");

        clock.advance(Duration.ofMinutes(5));
        check(reference);

        assertThat(payments.status(reference).orElseThrow().state()).isEqualTo(AttemptState.SUCCEEDED);
        assertThat(corrected("DECLINED", "SUCCEEDED")).isEqualTo(before + 1);
        assertThat(FakeProvider.chargeCount(reference)).isEqualTo(1);
    }

    @Test
    void aGenuineDeclineIsRecheckedWithBackoffForFortyEightHoursOnly() {
        double before = corrected("DECLINED", "SUCCEEDED");
        FakeProvider.mode("DECLINE_HARD(100)");
        String reference = collect();
        awaitState(reference, AttemptState.DECLINED, Duration.ofSeconds(10));
        FakeProvider.mode("NORMAL");
        Instant failedAt = clock.instant();
        assertThat(reconcileAt(reference)).isEqualTo(failedAt.plus(Duration.ofMinutes(5)));

        // 5 minutes, then 1 hour, then daily.
        clock.advance(Duration.ofMinutes(5));
        check(reference);
        assertThat(checks(reference)).isEqualTo(1);
        assertThat(reconcileAt(reference)).isEqualTo(clock.instant().plus(Duration.ofHours(1)));

        clock.advance(Duration.ofHours(1));
        check(reference);
        assertThat(checks(reference)).isEqualTo(2);
        assertThat(reconcileAt(reference)).isEqualTo(clock.instant().plus(Duration.ofDays(1)));

        // The next daily check would fall beyond 48 hours after the failure: checks stop.
        clock.advance(Duration.ofDays(1));
        check(reference);
        assertThat(checks(reference)).isEqualTo(3);
        assertThat(reconcileAt(reference)).isNull();

        assertThat(payments.status(reference).orElseThrow().state()).isEqualTo(AttemptState.DECLINED);
        assertThat(corrected("DECLINED", "SUCCEEDED")).isEqualTo(before);
        assertThat(FakeProvider.chargeCount(reference)).isEqualTo(1);
    }
}
