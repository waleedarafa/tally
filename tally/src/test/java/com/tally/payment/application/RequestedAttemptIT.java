package com.tally.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.support.AbstractReconcilingTest;
import com.tally.support.FakeProvider;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A REQUESTED attempt was never sent: the provider is called only after SENT commits (proposed ADR-026).
 * However old, it never becomes UNKNOWN; a long wait means the queue is stuck, and job health says so (D9).
 */
class RequestedAttemptIT extends AbstractReconcilingTest {

    @Autowired
    Reconciliation reconciliation;

    @Test
    void aRequestedAttemptOfAnyAgeIsNeverMadeUnknownAndItsWaitAlerts() {
        String reference = "REQ-" + UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        // No queue item: no worker sends it, so it waits REQUESTED for as long as the test needs.
        var madeAt = clock.instant().minus(Duration.ofDays(365)).atOffset(ZoneOffset.UTC);
        jdbc.update("INSERT INTO payment.payment_attempt (id, reference, account_id, amount_minor, currency,"
                + " payment_token, state, created_at, updated_at) VALUES (?, ?, ?, 999, 'USD', ?, 'REQUESTED', ?, ?)",
                attempt, reference, UUID.randomUUID(), FakeProvider.token(), madeAt, madeAt);
        try {
            reconciliation.run();

            assertThat(jdbc.queryForObject("SELECT state FROM payment.payment_attempt WHERE id = ?", String.class,
                    attempt)).isEqualTo("REQUESTED");
            assertThat(meters.get("tally.payment.requested.oldest_age_seconds").gauge().value())
                    .isGreaterThanOrEqualTo(Duration.ofDays(365).toSeconds());
            assertThat(meters.get("tally.payment.requested.stuck").gauge().value()).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM payment.payment_attempt WHERE id = ?", attempt);
        }
    }
}
