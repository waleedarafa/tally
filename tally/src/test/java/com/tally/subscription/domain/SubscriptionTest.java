package com.tally.subscription.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tally.subscription.EndReason;
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

    /** ENDED refuses everything (design.md §5, proposed in ADR-038): a renewal after the end changes nothing. */
    @Test
    void anEndedSubscriptionRefusesARenewal() {
        Subscription subscription = startedOn31January();

        assertThat(subscription.end(EndReason.WRITE_OFF, Instant.parse("2026-02-10T00:00:00Z"))).isTrue();

        assertThat(subscription.status()).isEqualTo(SubscriptionStatus.ENDED);
        assertThat(subscription.renew(2)).isFalse();
        assertThat(subscription.periods()).hasSize(1);
    }

    @Test
    void endingAnEndedSubscriptionChangesNothing() {
        Subscription subscription = startedOn31January();
        subscription.end(EndReason.FIRST_PAYMENT_DECLINED, Instant.parse("2026-01-31T10:01:00Z"));

        assertThat(subscription.end(EndReason.WRITE_OFF, Instant.parse("2026-03-02T00:00:00Z"))).isFalse();
        assertThat(subscription.endReason()).isEqualTo(EndReason.FIRST_PAYMENT_DECLINED);
        assertThat(subscription.endedAt()).isEqualTo(Instant.parse("2026-01-31T10:01:00Z"));
    }

    @Test
    void aNewSubscriptionIsActive() {
        assertThat(startedOn31January().status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(startedOn31January().endReason()).isNull();
    }

    @Test
    void anEndNeedsAKnownReason() {
        Subscription subscription = startedOn31January();

        assertThatThrownBy(() -> subscription.end(EndReason.UNKNOWN, Instant.parse("2026-02-10T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(subscription.status()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void aSignUpKeyDeterminesTheSubscriptionId() {
        UUID account = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID key = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        UUID otherKey = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        UUID otherAccount = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        Instant now = Instant.parse("2026-01-31T10:00:00Z");
        ZoneId utc = ZoneId.of("UTC");

        assertThat(Subscription.idOfSignUp(account, key))
                .isEqualTo(Subscription.idOfSignUp(account, key))
                .isEqualTo(Subscription.start(account, "BASIC", 1, now, utc, key).id());
        assertThat(Subscription.idOfSignUp(account, key)).isNotEqualTo(Subscription.idOfSignUp(account, otherKey));
        assertThat(Subscription.idOfSignUp(account, key)).isNotEqualTo(Subscription.idOfSignUp(otherAccount, key));
    }


    @Test
    void aStartOnTheAccountBillingDayIsAFullFirstPeriod() {
        Subscription subscription = Subscription.start(UUID.randomUUID(), "BASIC", 1,
                Instant.parse("2026-04-05T10:00:00Z"), ZoneId.of("UTC"), 5);

        assertThat(subscription.anchorDay()).isEqualTo(5);
        assertThat(subscription.currentPeriod())
                .isEqualTo(new SubscriptionPeriod(1, LocalDate.parse("2026-04-05"), LocalDate.parse("2026-05-05")));
    }

    @Test
    void aStartAfterTheAccountBillingDayIsAShortFirstPeriod() {
        Subscription subscription = Subscription.start(UUID.randomUUID(), "BASIC", 1,
                Instant.parse("2026-04-20T10:00:00Z"), ZoneId.of("UTC"), 5);

        assertThat(subscription.anchorDay()).isEqualTo(5);
        assertThat(subscription.currentPeriod())
                .isEqualTo(new SubscriptionPeriod(1, LocalDate.parse("2026-04-20"), LocalDate.parse("2026-05-05")));
    }

    @Test
    void aStartBeforeTheAccountBillingDayEndsOnIt() {
        Subscription subscription = Subscription.start(UUID.randomUUID(), "BASIC", 1,
                Instant.parse("2026-04-01T10:00:00Z"), ZoneId.of("UTC"), 5);

        assertThat(subscription.anchorDay()).isEqualTo(5);
        assertThat(subscription.currentPeriod())
                .isEqualTo(new SubscriptionPeriod(1, LocalDate.parse("2026-04-01"), LocalDate.parse("2026-04-05")));
    }

    @Test
    void theAnchorIsTheAccountBillingDayEvenWhenTheStartIsNotOnIt() {
        Subscription subscription = Subscription.start(UUID.randomUUID(), "BASIC", 1,
                Instant.parse("2026-02-15T10:00:00Z"), ZoneId.of("UTC"), 31);

        assertThat(subscription.anchorDay()).isEqualTo(31);
        assertThat(subscription.currentPeriod())
                .isEqualTo(new SubscriptionPeriod(1, LocalDate.parse("2026-02-15"), LocalDate.parse("2026-02-28")));
        assertThat(subscription.renew(2)).isTrue();
        assertThat(subscription.periods().get(1))
                .isEqualTo(new SubscriptionPeriod(2, LocalDate.parse("2026-02-28"), LocalDate.parse("2026-03-31")));
    }
}
