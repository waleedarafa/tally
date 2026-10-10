package com.tally.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.support.AbstractReconcilingTest;
import com.tally.support.FakeProvider;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Proposed ADR-026 and CONTRACT.md A11 / Q-B: against a provider whose lookup is not known to be
 * consistent, "not found" never means no charge. The attempt stays UNKNOWN, however old, and is never
 * retried: a lagging search read as "no charge" would allow a second charge.
 */
@TestPropertySource(properties = {
        "tally.payment.provider.lookup-consistent=false",
        "tally.test.context=inconsistent-lookup"
})
class InconsistentLookupIT extends AbstractReconcilingTest {

    @Test
    void notFoundLeavesAnOldUnknownAttemptUnknown() {
        String reference = "REC-" + UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        var now = clock.instant().atOffset(ZoneOffset.UTC);
        jdbc.update("INSERT INTO payment.payment_attempt (id, reference, account_id, amount_minor, currency,"
                + " payment_token, state, created_at, updated_at, sent_at, reconcile_at)"
                + " VALUES (?, ?, ?, 999, 'USD', ?, 'UNKNOWN', ?, ?, ?, ?)",
                attempt, reference, UUID.randomUUID(), FakeProvider.token(), now.minusDays(1), now.minusDays(1),
                now.minusDays(1), now);

        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                "SELECT reconcile_checks FROM payment.payment_attempt WHERE id = ?", Integer.class, attempt) == 1);

        assertThat(jdbc.queryForObject("SELECT state FROM payment.payment_attempt WHERE id = ?", String.class, attempt))
                .isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.payment_attempt WHERE reference = ?", Long.class,
                reference)).as("no retry").isEqualTo(1);
        assertThat(FakeProvider.requestCount(reference)).isZero();
    }
}
