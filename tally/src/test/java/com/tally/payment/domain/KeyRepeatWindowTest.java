package com.tally.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Proposed ADR-026 (b) and (c): an UNKNOWN attempt may be repeated with its key only while the key is
 * younger than the window, measured from when it was sent. Past it, the provider may have forgotten the
 * key, and a repeat would be a second charge (CONTRACT.md A23).
 */
class KeyRepeatWindowTest {

    static final Instant SENT = Instant.parse("2026-01-15T10:00:00Z");
    static final Duration WINDOW = Duration.ofHours(20);

    static PaymentAttempt unknownSentAt(Instant at) {
        PaymentAttempt attempt = PaymentAttempt.requested(UUID.randomUUID(), "REF", UUID.randomUUID(),
                Money.of(999, "USD"), "tok", at);
        attempt.sent(at);
        attempt.timedOut(at.plusSeconds(5));
        return attempt;
    }

    @Test
    void insideTheWindowTheKeyMayBeRepeated() {
        assertThat(unknownSentAt(SENT).mayRepeatKey(WINDOW, SENT.plus(Duration.ofMinutes(10)))).isTrue();
        assertThat(unknownSentAt(SENT).mayRepeatKey(WINDOW, SENT.plus(WINDOW).minusMillis(1))).isTrue();
    }

    @Test
    void fromTheEndOfTheWindowTheKeyIsNeverRepeated() {
        assertThat(unknownSentAt(SENT).mayRepeatKey(WINDOW, SENT.plus(WINDOW))).isFalse();
        assertThat(unknownSentAt(SENT).mayRepeatKey(WINDOW, SENT.plus(Duration.ofDays(30)))).isFalse();
    }
}
