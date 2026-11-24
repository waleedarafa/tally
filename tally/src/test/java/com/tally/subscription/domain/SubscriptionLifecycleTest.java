package com.tally.subscription.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.subscription.EndReason;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The lifecycle commands of design.md §5 (the billing lifecycle slice): trial start, trial conversion, a cancel
 * requested at period end, and the period ending while cancelling. Each one moves the subscription through the
 * transition table and changes nothing when the table refuses it.
 */
class SubscriptionLifecycleTest {

    static final Instant NOW = Instant.parse("2026-01-31T10:00:00Z");

    static Subscription trial() {
        return Subscription.startTrial(UUID.randomUUID(), "BASIC", 1, NOW, ZoneId.of("UTC"));
    }

    static Subscription started() {
        return Subscription.start(UUID.randomUUID(), "BASIC", 1, NOW, ZoneId.of("UTC"));
    }

    @Test
    void aTrialStartsInTrialWithPeriodOne() {
        Subscription trial = trial();

        assertThat(trial.status()).isEqualTo(SubscriptionStatus.TRIAL);
        assertThat(trial.currentPeriod().number()).isEqualTo(1);
        assertThat(trial.version()).isEqualTo(1);
        assertThat(trial.endReason()).isNull();
        assertThat(trial.endedAt()).isNull();

        // A plain start still starts in ACTIVE, unchanged.
        assertThat(started().status()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void trialConversionMovesTrialToActiveAndOnlyTrial() {
        Subscription trial = trial();

        assertThat(trial.endTrial()).isTrue();
        assertThat(trial.status()).isEqualTo(SubscriptionStatus.ACTIVE);

        assertThat(trial.endTrial()).isFalse();
        assertThat(trial.status()).isEqualTo(SubscriptionStatus.ACTIVE);

        Subscription active = started();
        assertThat(active.endTrial()).isFalse();
        assertThat(active.status()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void aCancelMovesTrialOrActiveToCancellingAndOnlyThose() {
        Subscription trial = trial();
        assertThat(trial.cancel()).isTrue();
        assertThat(trial.status()).isEqualTo(SubscriptionStatus.CANCELLING);

        Subscription active = started();
        assertThat(active.cancel()).isTrue();
        assertThat(active.status()).isEqualTo(SubscriptionStatus.CANCELLING);

        // A cancelling subscription is not cancelled twice, and does not renew after cancelling.
        assertThat(trial.cancel()).isFalse();
        assertThat(trial.renew(2)).isFalse();
        assertThat(trial.currentPeriod().number()).isEqualTo(1);

        // After the period ends, a cancelling subscription is not cancelled either.
        trial.periodEnded(Instant.parse("2026-02-01T00:00:00Z"));
        assertThat(trial.cancel()).isFalse();
        assertThat(trial.status()).isEqualTo(SubscriptionStatus.ENDED);
    }

    @Test
    void thePeriodEndingWhileCancellingEndsTheSubscription() {
        Subscription cancelling = started();
        cancelling.cancel();

        assertThat(cancelling.periodEnded(Instant.parse("2026-02-01T00:00:00Z"))).isTrue();
        assertThat(cancelling.status()).isEqualTo(SubscriptionStatus.ENDED);
        assertThat(cancelling.endedAt()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
        assertThat(cancelling.endReason()).isEqualTo(EndReason.CANCELLED);

        // ENDED refuses everything, an end included.
        assertThat(cancelling.periodEnded(Instant.parse("2026-02-02T00:00:00Z"))).isFalse();
        assertThat(cancelling.endedAt()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
        assertThat(cancelling.renew(2)).isFalse();
    }

    @Test
    void aPeriodEndIsRefusedByStatesTheTableDoesNotHoldItFor() {
        Subscription trial = trial();
        assertThat(trial.periodEnded(NOW)).isFalse();
        assertThat(trial.status()).isEqualTo(SubscriptionStatus.TRIAL);

        Subscription active = started();
        assertThat(active.periodEnded(NOW)).isFalse();
        assertThat(active.status()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void trialConversionDoesNotChangePeriodsOrVersions() {
        Subscription trial = trial();
        int version = trial.version();

        assertThat(trial.endTrial()).isTrue();

        assertThat(trial.currentPeriod().number()).isEqualTo(1);
        assertThat(trial.periods()).hasSize(1);
        assertThat(trial.version()).isEqualTo(version);
    }
}
