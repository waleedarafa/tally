package com.tally.subscription.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ADR-018: one table-driven test tries every state with every event. The expected table is written out
 * here, independently of {@code Subscription.TRANSITIONS}, from design.md §5 (the Subscription row) and
 * Design Record Step 16 (Trial, Active, Cancelling, Ended; renew(n) only for the current period; a late
 * cancel requested before period end ends it with renewal reversed; Ended refuses everything).
 */
class SubscriptionTransitionsTest {

    static final Map<SubscriptionStatus, Map<Subscription.Event, SubscriptionStatus>> EXPECTED = Map.of(
            SubscriptionStatus.TRIAL, Map.of(
                    Subscription.Event.TRIAL_ENDS, SubscriptionStatus.ACTIVE,
                    Subscription.Event.CANCEL_REQUESTED, SubscriptionStatus.CANCELLING,
                    Subscription.Event.FORCE_END, SubscriptionStatus.ENDED),
            SubscriptionStatus.ACTIVE, Map.of(
                    Subscription.Event.CANCEL_REQUESTED, SubscriptionStatus.CANCELLING,
                    Subscription.Event.RENEW, SubscriptionStatus.ACTIVE,
                    Subscription.Event.LATE_CANCEL_REQUESTED, SubscriptionStatus.ENDED,
                    Subscription.Event.FORCE_END, SubscriptionStatus.ENDED),
            SubscriptionStatus.CANCELLING, Map.of(
                    Subscription.Event.PERIOD_ENDS, SubscriptionStatus.ENDED,
                    Subscription.Event.FORCE_END, SubscriptionStatus.ENDED));

    @Test
    void everyStateWithEveryEvent() {
        List<String> tried = new ArrayList<>();
        for (SubscriptionStatus from : SubscriptionStatus.values()) {
            for (Subscription.Event event : Subscription.Event.values()) {
                SubscriptionStatus expected = EXPECTED.getOrDefault(from, Map.of()).get(event);
                assertThat(Subscription.next(from, event)).as(from + " + " + event)
                        .isEqualTo(Optional.ofNullable(expected));
                tried.add(from + "+" + event + "=" + (expected == null ? "refused" : expected));
            }
        }
        assertThat(tried).hasSize(SubscriptionStatus.values().length * Subscription.Event.values().length);
    }

    /** ENDED refuses everything: every event, including a force-end and a renewal. */
    @Test
    void endedRefusesEveryEvent() {
        for (Subscription.Event event : Subscription.Event.values()) {
            assertThat(Subscription.next(SubscriptionStatus.ENDED, event))
                    .as("ENDED + " + event).isEmpty();
        }
    }

    /** TRIAL is not renewed and does not end on a plain cancel; only a force-end ends it. */
    @Test
    void trialDoesNotRenewOrEndOnCancel() {
        for (Subscription.Event event : List.of(Subscription.Event.RENEW, Subscription.Event.LATE_CANCEL_REQUESTED,
                Subscription.Event.PERIOD_ENDS)) {
            assertThat(Subscription.next(SubscriptionStatus.TRIAL, event))
                    .as("TRIAL + " + event).isEmpty();
        }
    }

    /** Cancelling ends only on the period ending or a force-end; it is not renewed. */
    @Test
    void cancellingEndsOnlyOnPeriodEndOrForceEnd() {
        for (Subscription.Event event : List.of(Subscription.Event.RENEW, Subscription.Event.TRIAL_ENDS,
                Subscription.Event.CANCEL_REQUESTED, Subscription.Event.LATE_CANCEL_REQUESTED)) {
            assertThat(Subscription.next(SubscriptionStatus.CANCELLING, event))
                    .as("CANCELLING + " + event).isEmpty();
        }
    }
}
