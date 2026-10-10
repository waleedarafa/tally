package com.tally.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tally.payment.AttemptState;
import com.tally.payment.FailureReason;
import com.tally.payment.domain.PaymentAttempt.Event;
import com.tally.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ADR-018: one table-driven test tries every state with every event. The expected table is written out
 * here, independently of {@code PaymentAttempt.TRANSITIONS}, from design.md §5 plus the first run's
 * correction of a lying decline (FR-018, AT-6).
 */
class PaymentAttemptTransitionsTest {

    static final Map<AttemptState, Map<Event, AttemptState>> EXPECTED = Map.of(
            AttemptState.REQUESTED, Map.of(
                    Event.SEND, AttemptState.SENT),
            AttemptState.SENT, Map.of(
                    Event.CHARGED, AttemptState.SUCCEEDED,
                    Event.DECLINED, AttemptState.DECLINED,
                    Event.PROVIDER_ERROR, AttemptState.TECHNICAL_FAILURE,
                    Event.TIMED_OUT, AttemptState.UNKNOWN,
                    Event.STALE, AttemptState.UNKNOWN),
            AttemptState.UNKNOWN, Map.of(
                    Event.RESOLVED_CHARGED, AttemptState.SUCCEEDED,
                    Event.RESOLVED_DECLINED, AttemptState.DECLINED,
                    Event.RESOLVED_NO_CHARGE, AttemptState.TECHNICAL_FAILURE),
            AttemptState.DECLINED, Map.of(
                    Event.RESOLVED_CHARGED, AttemptState.SUCCEEDED));

    @Test
    void everyStateWithEveryEvent() {
        List<String> tried = new ArrayList<>();
        for (AttemptState from : AttemptState.values()) {
            for (Event event : Event.values()) {
                AttemptState expected = EXPECTED.getOrDefault(from, Map.of()).get(event);
                assertThat(PaymentAttempt.next(from, event)).as(from + " + " + event)
                        .isEqualTo(java.util.Optional.ofNullable(expected));
                tried.add(from + "+" + event + "=" + (expected == null ? "refused" : expected));
            }
        }
        System.out.println("TRANSITIONS " + tried.size() + " pairs: " + tried);
        assertThat(tried).hasSize(AttemptState.values().length * Event.values().length);
    }

    /** SUCCEEDED, TECHNICAL_FAILURE, the first run's states and UNRECOGNISED accept nothing. */
    @Test
    void finalAndForeignStatesRefuseEveryEvent() {
        for (AttemptState state : List.of(AttemptState.SUCCEEDED, AttemptState.TECHNICAL_FAILURE,
                AttemptState.PENDING, AttemptState.IN_FLIGHT, AttemptState.FAILED, AttemptState.UNRECOGNISED)) {
            for (Event event : Event.values()) {
                assertThat(PaymentAttempt.next(state, event)).as(state + " + " + event).isEmpty();
            }
        }
    }

    @Test
    void theAggregateRefusesATransitionTheTableDoesNotHold() {
        PaymentAttempt requested = PaymentAttempt.requested(UUID.randomUUID(), "REF", UUID.randomUUID(),
                Money.of(999, "USD"), "tok", Instant.parse("2026-01-15T10:00:00Z"));

        assertThatThrownBy(() -> requested.declined(FailureReason.HARD, Instant.parse("2026-01-15T10:00:01Z")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("REQUESTED: DECLINED is not allowed");
        assertThatThrownBy(() -> requested.retry(Instant.parse("2026-01-15T10:00:01Z")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(requested.state()).isEqualTo(AttemptState.REQUESTED);
    }

    /** Proposed ADR-026: a REQUESTED attempt was never sent, so no age makes it UNKNOWN. */
    @Test
    void aRequestedAttemptOfAnyAgeIsNeverMadeUnknown() {
        Instant made = Instant.parse("2026-01-15T10:00:00Z");
        PaymentAttempt requested = PaymentAttempt.requested(UUID.randomUUID(), "REF", UUID.randomUUID(),
                Money.of(999, "USD"), "tok", made);

        assertThatThrownBy(() -> requested.stale(made.plus(Duration.ofDays(3650))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("REQUESTED: STALE is not allowed");
        assertThat(requested.state()).isEqualTo(AttemptState.REQUESTED);
    }
}
