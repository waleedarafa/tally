package com.tally.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.shared.Money;
import com.tally.support.AbstractReconcilingTest;
import com.tally.support.FakeProvider;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** FR-018: unknown outcomes are reconciled, never retried; recent failures are re-checked. Own context. */
class ReconciliationIT extends AbstractReconcilingTest {


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
        double before = corrected("DECLINED", "SUCCEEDED");
        FakeProvider.mode("LIE");
        String reference = collect();

        awaitState(reference, AttemptState.DECLINED, Duration.ofSeconds(10));
        FakeProvider.mode("NORMAL");

        clock.advance(Duration.ofMinutes(5));
        awaitState(reference, AttemptState.SUCCEEDED, Duration.ofSeconds(10));
        assertThat(corrected("DECLINED", "SUCCEEDED")).isEqualTo(before + 1);
        assertThat(FakeProvider.chargeCount(reference)).isEqualTo(1);
    }

    /** Inserts an UNKNOWN attempt, sent at {@code sentAt}, due a check now. Returns its ID. */
    UUID unknownAttempt(String reference, Instant sentAt) {
        UUID id = UUID.randomUUID();
        var now = clock.instant().atOffset(ZoneOffset.UTC);
        jdbc.update("INSERT INTO payment.payment_attempt (id, reference, account_id, amount_minor, currency,"
                + " payment_token, state, created_at, updated_at, sent_at, reconcile_at)"
                + " VALUES (?, ?, ?, 999, 'USD', ?, 'UNKNOWN', ?, ?, ?, ?)",
                id, reference, UUID.randomUUID(), FakeProvider.token(), now, now, sentAt.atOffset(ZoneOffset.UTC), now);
        return id;
    }

    String stateOf(UUID attempt) {
        return jdbc.queryForObject("SELECT state FROM payment.payment_attempt WHERE id = ?", String.class, attempt);
    }

    /**
     * Proposed ADR-026: the fake's lookup is consistent, and the attempt was sent long enough ago for a
     * charge to show. "Not found" is then no charge: a technical failure, no event, and a new attempt.
     */
    @Test
    void anUnknownAttemptTheProviderNeverChargedIsATechnicalFailureAndIsTriedAgain() {
        double before = corrected("UNKNOWN", "TECHNICAL_FAILURE");
        String reference = "REC-" + UUID.randomUUID();
        UUID unknown = unknownAttempt(reference, clock.instant().minus(Duration.ofMinutes(10)));

        await().atMost(Duration.ofSeconds(10)).until(() -> "TECHNICAL_FAILURE".equals(stateOf(unknown)));
        assertThat(corrected("UNKNOWN", "TECHNICAL_FAILURE")).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM payment.payment_attempt WHERE id = ?", String.class,
                unknown)).as("no decline recorded").isNull();
        // The retry, a new attempt, charges once.
        awaitState(reference, AttemptState.SUCCEEDED, Duration.ofSeconds(10));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.payment_attempt WHERE reference = ?",
                Long.class, reference)).isEqualTo(2);
        assertThat(FakeProvider.chargeCount(reference)).isEqualTo(1);
    }

    /** A charge sent too recently to show in the lookup is not "no charge": the attempt stays UNKNOWN. */
    @Test
    void anUnknownAttemptSentTooRecentlyStaysUnknown() {
        String reference = "REC-" + UUID.randomUUID();
        UUID unknown = unknownAttempt(reference, clock.instant());

        await().atMost(Duration.ofSeconds(10)).until(() -> checks(reference) == 1);
        assertThat(stateOf(unknown)).isEqualTo("UNKNOWN");
        assertThat(FakeProvider.requestCount(reference)).isZero();
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
        await().atMost(Duration.ofSeconds(10)).until(() -> checks(reference) == 1);
        assertThat(reconcileAt(reference)).isEqualTo(clock.instant().plus(Duration.ofHours(1)));

        clock.advance(Duration.ofHours(1));
        await().atMost(Duration.ofSeconds(10)).until(() -> checks(reference) == 2);
        assertThat(reconcileAt(reference)).isEqualTo(clock.instant().plus(Duration.ofDays(1)));

        // The next daily check would fall beyond 48 hours after the failure: checks stop.
        clock.advance(Duration.ofDays(1));
        await().atMost(Duration.ofSeconds(10)).until(() -> checks(reference) == 3);
        assertThat(reconcileAt(reference)).isNull();

        assertThat(payments.status(reference).orElseThrow().state()).isEqualTo(AttemptState.DECLINED);
        assertThat(corrected("DECLINED", "SUCCEEDED")).isEqualTo(before);
        assertThat(FakeProvider.chargeCount(reference)).isEqualTo(1);
    }
}
