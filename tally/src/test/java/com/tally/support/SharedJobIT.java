package com.tally.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * {@link SharedJob}: another test context, whose clock is ahead, can claim a shared job row and reschedule it
 * into its own future (AT-15, run 38153130900). A wait that re-arms the row at this context's clock still sees
 * the job run here, on schedule.
 */
class SharedJobIT extends AbstractReconcilingTest {

    Instant lastSuccess() {
        OffsetDateTime at = jdbc.queryForObject(
                "SELECT last_success_at FROM payment.jobs WHERE queue_key = 'payment-reconciliation'", OffsetDateTime.class);
        return at == null ? Instant.EPOCH : at.toInstant();
    }

    @Test
    void aJobRescheduledByAnotherContextStillRunsOnThisContextsClock() {
        // As another context two years ahead would leave it after its own run.
        jdbc.update("UPDATE payment.jobs SET status = 'READY', claim_token = NULL, due_at = ?"
                + " WHERE queue_key = 'payment-reconciliation'", clock.instant().plus(Duration.ofDays(730))
                .atOffset(ZoneOffset.UTC));
        clock.advance(Duration.ofMinutes(5));
        Instant now = clock.instant();

        SharedJob.RECONCILIATION.await(jdbc, clock, Duration.ofSeconds(10), () -> !lastSuccess().isBefore(now));

        assertThat(lastSuccess()).isAfterOrEqualTo(now);
    }
}
