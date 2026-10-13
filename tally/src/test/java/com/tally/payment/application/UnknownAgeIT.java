package com.tally.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.support.AbstractReconcilingTest;
import com.tally.support.FakeProvider;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A1: {@code tally.payment.unknown.oldest_age_seconds} is the age of the oldest UNKNOWN attempt, counted from
 * when it was sent, and 0 when there is none. D2: an unknown outcome is resolved within 1 hour.
 */
class UnknownAgeIT extends AbstractReconcilingTest {

    double oldestUnknownAge() {
        return meters.get("tally.payment.unknown.oldest_age_seconds").gauge().value();
    }

    UUID unknownSent(Duration ago) {
        UUID attempt = UUID.randomUUID();
        String reference = "AGE-" + attempt;
        var sentAt = clock.instant().minus(ago).atOffset(ZoneOffset.UTC);
        // No reconcile_at: reconciliation leaves the attempt alone, so it stays UNKNOWN for the test.
        jdbc.update("INSERT INTO payment.payment_attempt (id, reference, account_id, amount_minor, currency,"
                + " payment_token, state, created_at, updated_at, sent_at, idempotency_key)"
                + " VALUES (?, ?, ?, 999, 'USD', ?, 'UNKNOWN', ?, ?, ?, ?)",
                attempt, reference, UUID.randomUUID(), FakeProvider.token(), sentAt, sentAt, sentAt, reference + ":1");
        return attempt;
    }

    @Test
    void theOldestUnknownAttemptsAgeAndZeroWhenThereIsNone() {
        assertThat(oldestUnknownAge()).isZero();

        Instant start = clock.instant();
        UUID older = unknownSent(Duration.ofMinutes(50));
        unknownSent(Duration.ofMinutes(10));
        assertThat(oldestUnknownAge()).isEqualTo(Duration.ofMinutes(50).toSeconds());

        clock.advance(Duration.ofMinutes(15));
        assertThat(oldestUnknownAge()).isEqualTo(Duration.ofMinutes(65).toSeconds());

        // Resolved: the next oldest counts.
        jdbc.update("UPDATE payment.payment_attempt SET state = 'SUCCEEDED' WHERE id = ?", older);
        assertThat(oldestUnknownAge()).isEqualTo(Duration.between(start.minus(Duration.ofMinutes(10)),
                clock.instant()).toSeconds());

        jdbc.update("UPDATE payment.payment_attempt SET state = 'SUCCEEDED' WHERE state = 'UNKNOWN'");
        assertThat(oldestUnknownAge()).isZero();
    }
}
