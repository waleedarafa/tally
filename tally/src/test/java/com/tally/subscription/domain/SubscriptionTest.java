package com.tally.subscription.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubscriptionTest {

    static Subscription startedOn31January() {
        return Subscription.start(UUID.randomUUID(), "BASIC", 1, Instant.parse("2026-01-31T10:00:00Z"), ZoneId.of("UTC"));
    }

    @Test
    void startsWithPeriodOneFromTheLocalDayItStarts() {
        // 22:00 UTC on 31 January is already 1 February in Dubai.
        Subscription dubai = Subscription.start(UUID.randomUUID(), "BASIC", 3, Instant.parse("2026-01-31T22:00:00Z"),
                ZoneId.of("Asia/Dubai"));

        assertThat(dubai.currentPeriod())
                .isEqualTo(new SubscriptionPeriod(1, LocalDate.parse("2026-02-01"), LocalDate.parse("2026-03-01")));
        assertThat(dubai.offerVersion()).isEqualTo(3);
        assertThat(dubai.version()).isEqualTo(1);
    }

    /** S2: the next period starts where the last ended; S5: on the account's billing day, kept through short months. */
    @Test
    void eachRenewalStartsWhereTheLastPeriodEnded() {
        Subscription subscription = startedOn31January();

        assertThat(subscription.renew(2)).isTrue();
        assertThat(subscription.renew(3)).isTrue();

        assertThat(subscription.periods()).containsExactly(
                new SubscriptionPeriod(1, LocalDate.parse("2026-01-31"), LocalDate.parse("2026-02-28")),
                new SubscriptionPeriod(2, LocalDate.parse("2026-02-28"), LocalDate.parse("2026-03-31")),
                new SubscriptionPeriod(3, LocalDate.parse("2026-03-31"), LocalDate.parse("2026-04-30")));
    }

    /** ADR-003: renew(n) does nothing if period n was already renewed, so a repeated renewal is harmless. */
    @Test
    void renewingThePeriodAgainChangesNothing() {
        Subscription subscription = startedOn31January();
        subscription.renew(2);

        assertThat(subscription.renew(2)).isFalse();
        assertThat(subscription.renew(1)).isFalse();
        assertThat(subscription.periods()).hasSize(2);
    }

    @Test
    void aRenewalCannotSkipAPeriod() {
        Subscription subscription = startedOn31January();

        assertThatThrownBy(() -> subscription.renew(3)).isInstanceOf(IllegalStateException.class);
        assertThat(subscription.periods()).hasSize(1);
    }
}
