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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * FR-018: unknown outcomes are reconciled, never retried; recent failures are re-checked.
 *
 * <p>Each check is a direct call, so this class's scheduled reconciliation must not run between two of them: it did
 * in 3 of 10 runs of the test-isolation rollout, once the job was no longer parked in the year 9999 (PR #23 review).
 * Its context schedules reconciliation every ten years instead: the job runs once as the context starts (the base
 * class waits for that run), then never within the few days these tests move the clock. A setting, not a rewrite of
 * the job's row (ADR-029, proposed; F13).
 */
@TestPropertySource(properties = "tally.payment.reconciliation.interval=3650d")
class ReconciliationIT extends AbstractReconcilingTest {

    @Autowired
    Reconciliation reconciliation;

    @Autowired
    PaymentAttempts attempts;

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

    /**
     * The lookup is by reference, and shows every attempt's charges. A retry's charge must not correct the
     * earlier decline: it settles the reference, and the superseded decline is no longer re-checked (AT-16 run
     * of 2026-10-11: "Corrected INV-1125... from DECLINED to SUCCEEDED").
     */
    @Test
    void aRetrysChargeDoesNotCorrectTheDeclineBeforeIt() {
        double before = corrected("DECLINED", "SUCCEEDED");
        FakeProvider.mode("DECLINE_HARD(100)");
        String reference = collect();
        awaitState(reference, AttemptState.DECLINED, Duration.ofSeconds(10));
        FakeProvider.mode("NORMAL");
        UUID declined = attemptId(reference, 1);

        payments.collectAgain(reference, 1);
        awaitState(reference, AttemptState.SUCCEEDED, Duration.ofSeconds(10));
        clock.advance(Duration.ofMinutes(5));
        reconciliation.check(attempts.find(declined).orElseThrow());

        assertThat(jdbc.queryForList("SELECT number || '=' || state FROM payment.payment_attempt"
                + " WHERE reference = ? ORDER BY number", String.class, reference))
                .containsExactly("1=DECLINED", "2=SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT reconcile_at FROM payment.payment_attempt WHERE id = ?",
                OffsetDateTime.class, declined)).as("a superseded decline is no longer re-checked").isNull();
        assertThat(corrected("DECLINED", "SUCCEEDED")).isEqualTo(before);
    }

    UUID attemptId(String reference, int number) {
        return jdbc.queryForObject("SELECT id FROM payment.payment_attempt WHERE reference = ? AND number = ?",
                UUID.class, reference, number);
    }
}
