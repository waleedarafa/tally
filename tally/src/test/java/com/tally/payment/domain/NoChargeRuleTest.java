package com.tally.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Proposed ADR-026 and CONTRACT.md A11 / Q-B: a lookup that found nothing proves no charge only from a
 * consistent lookup, and only once a charge would show. Otherwise the attempt stays UNKNOWN.
 */
class NoChargeRuleTest {

    static final Instant SENT = Instant.parse("2026-01-15T10:00:00Z");
    static final Duration VISIBLE_AFTER = Duration.ofSeconds(6);

    static PaymentAttempt sentAt(Instant at) {
        PaymentAttempt attempt = PaymentAttempt.requested(UUID.randomUUID(), "REF", UUID.randomUUID(),
                Money.of(999, "USD"), "tok", at);
        attempt.sent(at);
        attempt.timedOut(at.plusSeconds(5));
        return attempt;
    }

    @Test
    void aConsistentLookupOldEnoughProvesNoCharge() {
        assertThat(sentAt(SENT).noChargeIsCertain(true, VISIBLE_AFTER, SENT.plus(VISIBLE_AFTER))).isTrue();
        assertThat(sentAt(SENT).noChargeIsCertain(true, VISIBLE_AFTER, SENT.plus(Duration.ofDays(1)))).isTrue();
    }

    @Test
    void aChargeTooRecentToShowIsNotProvenAbsent() {
        assertThat(sentAt(SENT).noChargeIsCertain(true, VISIBLE_AFTER, SENT.plus(VISIBLE_AFTER).minusMillis(1)))
                .isFalse();
    }

    /** A lagging search read as "no charge" would allow a second charge: never, however old the attempt. */
    @Test
    void anInconsistentLookupNeverProvesNoCharge() {
        assertThat(sentAt(SENT).noChargeIsCertain(false, VISIBLE_AFTER, SENT.plus(Duration.ofDays(30)))).isFalse();
    }
}
