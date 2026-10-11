package com.tally.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.payment.domain.PaymentAttempts;
import com.tally.support.AbstractReconcilingTest;
import com.tally.support.FakeProvider;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proposed ADR-026, PR #14/#15 review D, CONTRACT.md A22/A23: how an UNKNOWN attempt is resolved, in order.
 * (a) Look the reference up; a charge found decides. (b) If none is found and the attempt's key is younger
 * than the repeat window (the provider's retention minus a margin, 20 hours), repeat the charge with the
 * same key and the stored parameters; a replay or a fresh outcome decides; a refusal leaves it UNKNOWN.
 * (c) If the key is older, never repeat: it stays UNKNOWN and raises an alert, for settlement
 * reconciliation (proposed ADR-021).
 *
 * <p>The fake's lookup is consistent, so the fake's {@code LOOKUP_LAG} mode stands in for a provider whose
 * search lags (Q-B). Each test checks one attempt by calling reconciliation directly; the attempt has no
 * reconcile time, so no background job in any test context reaches it first.
 */
class UnknownResolutionIT extends AbstractReconcilingTest {

    @Autowired
    Reconciliation reconciliation;

    @Autowired
    PaymentAttempts attempts;

    String reference;

    @AfterEach
    void cleanUp() {
        FakeProvider.mode("NORMAL");
        // An attempt left UNKNOWN would stay in every later reconciliation run on the shared database.
        jdbc.update("DELETE FROM payment.payment_attempt WHERE reference = ? AND state = 'UNKNOWN'", reference);
    }

    /** An UNKNOWN attempt sent at {@code sentAt} with key {@code reference:1}, that only a direct check reaches. */
    UUID unknownAttempt(String token, Instant sentAt) {
        UUID id = UUID.randomUUID();
        var now = clock.instant().atOffset(ZoneOffset.UTC);
        jdbc.update("INSERT INTO payment.payment_attempt (id, reference, account_id, amount_minor, currency,"
                + " payment_token, state, created_at, updated_at, sent_at, idempotency_key)"
                + " VALUES (?, ?, ?, 999, 'USD', ?, 'UNKNOWN', ?, ?, ?, ?)",
                id, reference, UUID.randomUUID(), token, now, now, sentAt.atOffset(ZoneOffset.UTC), reference + ":1");
        return id;
    }

    void check(UUID attempt) {
        reconciliation.check(attempts.find(attempt).orElseThrow());
    }

    String stateOf(UUID attempt) {
        return jdbc.queryForObject("SELECT state FROM payment.payment_attempt WHERE id = ?", String.class, attempt);
    }

    long attemptsForReference() {
        return jdbc.queryForObject("SELECT count(*) FROM payment.payment_attempt WHERE reference = ?", Long.class,
                reference);
    }

    /** (b) The first request never reached the provider: the repeat charges now, once, on the same attempt. */
    @Test
    void neverExecutedTheRepeatWithTheKeyChargesNow() {
        reference = "RES-" + UUID.randomUUID();
        UUID attempt = unknownAttempt(FakeProvider.token(), clock.instant().minus(Duration.ofMinutes(10)));

        check(attempt);

        assertThat(stateOf(attempt)).isEqualTo("SUCCEEDED");
        assertThat(attemptsForReference()).as("no new attempt").isEqualTo(1);
        assertThat(FakeProvider.chargeCount(reference)).isEqualTo(1);
    }

    /** (b) The lookup lags behind a charge made with the key: the repeat replays it, charging nothing more. */
    @Test
    void aLaggingLookupIsAnsweredByTheRepeatWhichReplaysTheCharge() {
        reference = "RES-" + UUID.randomUUID();
        String token = FakeProvider.token();
        Map<String, Object> first = FakeProvider.charge(token, 999, "USD", reference, reference + ":1");
        FakeProvider.mode("LOOKUP_LAG");
        UUID attempt = unknownAttempt(token, clock.instant().minus(Duration.ofMinutes(10)));
        long replayed = FakeProvider.stats().get("replayed");

        check(attempt);

        assertThat(stateOf(attempt)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT charge_id FROM payment.payment_attempt WHERE id = ?", String.class,
                attempt)).isEqualTo(first.get("chargeId"));
        assertThat(FakeProvider.stats().get("replayed")).isEqualTo(replayed + 1);
        assertThat(FakeProvider.chargeCount(reference)).as("charged once").isEqualTo(1);
    }

    /**
     * (c) The key is older than the window: the provider may have forgotten it (pruned here, as after its
     * 24-hour retention), and a repeat would charge a second time. Tally never repeats: the attempt stays
     * UNKNOWN, nothing is sent, and the alert counts it.
     */
    @Test
    void pastTheWindowTheKeyIsNeverRepeatedAndTheAttemptStaysUnknownWithAnAlert() {
        reference = "RES-" + UUID.randomUUID();
        String token = FakeProvider.token();
        FakeProvider.charge(token, 999, "USD", reference, reference + ":1");
        FakeProvider.pruneIdempotencyKeys();
        FakeProvider.mode("LOOKUP_LAG");
        UUID attempt = unknownAttempt(token, clock.instant().minus(Duration.ofHours(21)));
        int requests = FakeProvider.requestCount(reference);

        check(attempt);

        assertThat(stateOf(attempt)).isEqualTo("UNKNOWN");
        assertThat(FakeProvider.requestCount(reference)).as("never repeated").isEqualTo(requests);
        assertThat(FakeProvider.chargeCount(reference)).as("no second charge").isEqualTo(1);
        assertThat(meters.get("tally.payment.unknown_past_key_window").gauge().value()).isGreaterThanOrEqualTo(1);
    }

    /**
     * (a) A decline found by reference decides only a first attempt: a retry's lookup also shows the decline
     * of the attempt before it. Attempt 2 learns its own outcome by repeating with its own key.
     */
    @Test
    void anEarlierAttemptsDeclineDoesNotDecideALaterAttempt() {
        reference = "RES-" + UUID.randomUUID();
        String token = FakeProvider.token();
        FakeProvider.mode("DECLINE_HARD(100)");
        FakeProvider.charge(token, 999, "USD", reference, reference + ":1");
        FakeProvider.mode("NORMAL");
        UUID second = UUID.randomUUID();
        var now = clock.instant().atOffset(ZoneOffset.UTC);
        jdbc.update("INSERT INTO payment.payment_attempt (id, reference, number, account_id, amount_minor, currency,"
                + " payment_token, state, created_at, updated_at, sent_at, idempotency_key)"
                + " VALUES (?, ?, 2, ?, 999, 'USD', ?, 'UNKNOWN', ?, ?, ?, ?)",
                second, reference, UUID.randomUUID(), token, now, now, now.minusMinutes(10), reference + ":2");

        check(second);

        assertThat(stateOf(second)).isEqualTo("SUCCEEDED");
    }

    /** (b) The repeat is refused (503): no answer yet, so the attempt stays UNKNOWN and is checked again later. */
    @Test
    void aRefusedRepeatLeavesTheAttemptUnknownForALaterCheck() {
        reference = "RES-" + UUID.randomUUID();
        UUID attempt = unknownAttempt(FakeProvider.token(), clock.instant().minus(Duration.ofMinutes(10)));
        FakeProvider.mode("OUTAGE");

        check(attempt);

        assertThat(stateOf(attempt)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT reconcile_checks FROM payment.payment_attempt WHERE id = ?",
                Integer.class, attempt)).isEqualTo(1);
        assertThat(FakeProvider.chargeCount(reference)).isZero();
    }
}
