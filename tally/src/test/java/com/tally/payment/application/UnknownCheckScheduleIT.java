package com.tally.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.payment.domain.PaymentAttempt;
import com.tally.payment.domain.PaymentAttempts;
import com.tally.shared.Money;
import com.tally.support.AbstractReconcilingTest;
import com.tally.support.FakeProvider;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Characterisation, not a specification: when reconciliation checks an UNKNOWN attempt whose checks change nothing,
 * as ReconciliationSchedule sets it today (5 minutes, then 1 hour later, then daily). Evidence for the schedule's
 * decision (#50, item 3); D2 expects an unknown outcome resolved within 1 hour, and ADR-026's repeat by key is only
 * allowed for 20 hours.
 *
 * <p>The fake provider cannot fail a lookup (CONTRACT.md A14: its outage covers charges only). So every check here
 * takes the same "nothing changed" path a failed lookup takes, another way: the lookup lags and shows nothing
 * ({@code LOOKUP_LAG}), and the repeat with the key gets a 503 ({@code OUTAGE}). Each check therefore sends exactly one
 * charge request, which the fake counts; that count is how the test sees a check happen.
 *
 * <p>Checks are direct calls to reconciliation as the test moves its clock; this class's scheduled reconciliation
 * runs once as the context starts, then never within the 20 hours the clock moves.
 */
@TestPropertySource(properties = "tally.payment.reconciliation.interval=3650d")
class UnknownCheckScheduleIT extends AbstractReconcilingTest {

    @Autowired
    Reconciliation reconciliation;

    @Autowired
    PaymentAttempts attempts;

    @Autowired
    TransactionTemplate transactions;

    String reference;

    @AfterEach
    void normalProvider() {
        FakeProvider.mode("NORMAL");
        jdbc.update("DELETE FROM payment.payment_attempt WHERE reference = ? AND state = 'UNKNOWN'", reference);
    }

    @Test
    void anUnknownAttemptWhoseFirstCheckFailsIsNextCheckedAt65MinutesAndThenNotUntilAfterTheKeyWindow() {
        reference = "SCHED-" + UUID.randomUUID();
        Instant t0 = clock.instant();
        // Becomes UNKNOWN the way a timed-out call makes it: sent at t0, its call timed out at t0. No collection
        // request: a check that changes nothing never reads it.
        UUID id = transactions.execute(tx -> {
            PaymentAttempt attempt = PaymentAttempt.requested(null, reference, UUID.randomUUID(),
                    Money.of(999, "USD"), FakeProvider.token(), t0);
            attempt.sent(t0);
            attempt.timedOut(t0);
            attempts.add(attempt);
            return attempt.id();
        });
        FakeProvider.mode("LOOKUP_LAG", "OUTAGE");

        List<Duration> checks = new ArrayList<>();
        String stateAt60Minutes = null;
        int requests = FakeProvider.requestCount(reference);
        Duration window = Duration.ofHours(20);
        // A step a minute to 70 minutes, then every 10 minutes: a check due between two steps still runs, and is
        // seen, at the next one (reconciliation takes every attempt that is due), so no check can be missed.
        for (Duration elapsed = Duration.ofMinutes(1); elapsed.compareTo(window) <= 0;
                elapsed = elapsed.plusMinutes(elapsed.compareTo(Duration.ofMinutes(70)) < 0 ? 1 : 10)) {
            clock.set(t0.plus(elapsed));
            reconciliation.run();
            int now = FakeProvider.requestCount(reference);
            if (now != requests) {
                checks.add(elapsed);
                requests = now;
            }
            if (elapsed.equals(Duration.ofMinutes(60))) {
                stateAt60Minutes = state(id);
            }
        }
        Instant nextCheck = jdbc.queryForObject("SELECT reconcile_at FROM payment.payment_attempt WHERE id = ?",
                OffsetDateTime.class, id).toInstant();

        // (a) The first check, at 5 minutes, changes nothing; the next is 1 hour later, so D2's hour passes.
        assertThat(checks).startsWith(Duration.ofMinutes(5), Duration.ofMinutes(65));
        assertThat(stateAt60Minutes).isEqualTo("UNKNOWN");
        // (b) Nothing checks it again before the key window ends at 20 hours: the next check is a day later.
        assertThat(checks).containsExactly(Duration.ofMinutes(5), Duration.ofMinutes(65));
        assertThat(Duration.between(t0, nextCheck)).isEqualTo(Duration.ofMinutes(65).plusDays(1));
        assertThat(state(id)).isEqualTo("UNKNOWN");
    }

    String state(UUID attempt) {
        return jdbc.queryForObject("SELECT state FROM payment.payment_attempt WHERE id = ?", String.class, attempt);
    }
}
