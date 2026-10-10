package com.tally.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.payment.AttemptState;
import com.tally.payment.domain.PaymentAttempts;
import com.tally.support.AbstractReconcilingTest;
import com.tally.support.FakeProvider;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Proposed ADR-026 and CONTRACT.md A11 / Q-B: what a lookup that found nothing means for an UNKNOWN
 * attempt. Each test checks one attempt by calling reconciliation directly. The attempt is inserted with
 * no reconcile time, so no background job in any test context, whatever its clock or settings, can
 * reach it first.
 */
class NotFoundLookupIT {

    abstract static class Base extends AbstractReconcilingTest {

        @Autowired
        Reconciliation reconciliation;

        @Autowired
        PaymentAttempts attempts;

        /** An UNKNOWN attempt, sent at {@code sentAt}, that only a direct check reaches. */
        UUID unknownAttempt(String reference, Instant sentAt) {
            UUID id = UUID.randomUUID();
            var now = clock.instant().atOffset(ZoneOffset.UTC);
            jdbc.update("INSERT INTO payment.payment_attempt (id, reference, account_id, amount_minor, currency,"
                    + " payment_token, state, created_at, updated_at, sent_at, idempotency_key)"
                    + " VALUES (?, ?, ?, 999, 'USD', ?, 'UNKNOWN', ?, ?, ?, ?)",
                    id, reference, UUID.randomUUID(), FakeProvider.token(), now, now, sentAt.atOffset(ZoneOffset.UTC),
                    reference + ":1");
            return id;
        }

        void check(UUID attempt) {
            reconciliation.check(attempts.find(attempt).orElseThrow());
        }

        String stateOf(UUID attempt) {
            return jdbc.queryForObject("SELECT state FROM payment.payment_attempt WHERE id = ?", String.class, attempt);
        }

        long attemptsFor(String reference) {
            return jdbc.queryForObject("SELECT count(*) FROM payment.payment_attempt WHERE reference = ?",
                    Long.class, reference);
        }

        /** Keeps a left-UNKNOWN attempt out of other tests' reconciliation runs. */
        void remove(String reference) {
            jdbc.update("DELETE FROM payment.payment_attempt WHERE reference = ?", reference);
        }
    }

    /** The fake's lookup is consistent (application.yml). */
    @Nested
    class ConsistentLookup extends Base {

        /** Sent long enough ago for a charge to show: no charge. A technical failure, no event, a new attempt. */
        @Test
        void anUnknownAttemptTheProviderNeverChargedIsATechnicalFailureAndIsTriedAgain() {
            double before = corrected("UNKNOWN", "TECHNICAL_FAILURE");
            String reference = "REC-" + UUID.randomUUID();
            UUID unknown = unknownAttempt(reference, clock.instant().minus(Duration.ofMinutes(10)));

            check(unknown);

            assertThat(stateOf(unknown)).isEqualTo("TECHNICAL_FAILURE");
            assertThat(corrected("UNKNOWN", "TECHNICAL_FAILURE")).isEqualTo(before + 1);
            assertThat(jdbc.queryForObject("SELECT failure_reason FROM payment.payment_attempt WHERE id = ?",
                    String.class, unknown)).as("no decline recorded").isNull();
            // The retry, a new attempt, charges once.
            await().atMost(Duration.ofSeconds(10)).until(() ->
                    payments.status(reference).map(s -> s.state() == AttemptState.SUCCEEDED).orElse(false));
            assertThat(attemptsFor(reference)).isEqualTo(2);
            assertThat(FakeProvider.chargeCount(reference)).isEqualTo(1);
        }

        /** A charge sent too recently to show in the lookup is not "no charge": the attempt stays UNKNOWN. */
        @Test
        void anUnknownAttemptSentTooRecentlyStaysUnknown() {
            String reference = "REC-" + UUID.randomUUID();
            UUID unknown = unknownAttempt(reference, clock.instant());
            try {
                check(unknown);

                assertThat(stateOf(unknown)).isEqualTo("UNKNOWN");
                assertThat(attemptsFor(reference)).as("no retry").isEqualTo(1);
                assertThat(FakeProvider.requestCount(reference)).isZero();
            } finally {
                remove(reference);
            }
        }
    }

    /** A provider whose lookup is not known to be consistent: "not found" never means no charge. */
    @Nested
    @TestPropertySource(properties = {
            "tally.payment.provider.lookup-consistent=false",
            "tally.test.context=inconsistent-lookup"
    })
    class InconsistentLookup extends Base {

        /** However old, the attempt stays UNKNOWN and is never retried: a lagging search allows a second charge. */
        @Test
        void notFoundLeavesAnOldUnknownAttemptUnknown() {
            String reference = "REC-" + UUID.randomUUID();
            UUID unknown = unknownAttempt(reference, clock.instant().minus(Duration.ofDays(1)));
            try {
                check(unknown);

                assertThat(stateOf(unknown)).isEqualTo("UNKNOWN");
                assertThat(attemptsFor(reference)).as("no retry").isEqualTo(1);
                assertThat(FakeProvider.requestCount(reference)).isZero();
            } finally {
                remove(reference);
            }
        }
    }
}
