package com.tally.subscription.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubscriptionChangePlanTest {

    static final Instant NOW = Instant.parse("2026-01-16T10:00:00Z");

    static Subscription active() {
        return Subscription.start(UUID.randomUUID(), "BASIC", 1, NOW, ZoneId.of("UTC"));
    }

    @Test
    void anActiveSubscriptionChangesPlan() {
        Subscription subscription = active();

        assertThat(subscription.changePlan("PRO", 2)).isTrue();
        assertThat(subscription.planId()).isEqualTo("PRO");
        assertThat(subscription.offerVersion()).isEqualTo(2);
        assertThat(subscription.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subscription.currentPeriod().number()).isEqualTo(1);
    }

    @Test
    void theSamePlanAndVersionChangesNothing() {
        Subscription subscription = active();

        assertThat(subscription.changePlan("BASIC", 1)).isFalse();
        assertThat(subscription.planId()).isEqualTo("BASIC");
        assertThat(subscription.offerVersion()).isEqualTo(1);
    }

    @Test
    void onlyAnActiveSubscriptionChangesPlan() {
        Subscription trial = Subscription.startTrial(UUID.randomUUID(), "BASIC", 1, NOW, ZoneId.of("UTC"));
        assertThat(trial.changePlan("PRO", 2)).isFalse();
        assertThat(trial.planId()).isEqualTo("BASIC");

        Subscription cancelling = active();
        cancelling.cancel();
        assertThat(cancelling.changePlan("PRO", 2)).isFalse();
        assertThat(cancelling.planId()).isEqualTo("BASIC");
    }

    @Test
    void offerVersionsAreNumberedFromOne() {
        Subscription subscription = active();
        assertThatThrownBy(() -> subscription.changePlan("PRO", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from 1");
    }
}
