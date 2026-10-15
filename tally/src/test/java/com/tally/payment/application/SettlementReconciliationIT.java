package com.tally.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.support.AbstractReconcilingTest;
import com.tally.support.FakeProvider;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Settlement reconciliation (proposed 021, decided 2026-10-14; D14): Tally reads the provider's settlement batches
 * and compares them with its own attempts, in both directions.
 * <ul>
 *   <li>A charge in a batch for the reference of an UNKNOWN attempt decides it: SUCCEEDED.</li>
 *   <li>A charge Tally has no successful attempt for, or one whose amount differs, is a mismatch.</li>
 *   <li>A success Tally recorded that no batch shows, after {@code expected-within}, is a mismatch.</li>
 *   <li>An UNKNOWN attempt that no batch shows, after {@code expected-within}, is never decided: it is a mismatch
 *       for a person ("not found alone never means no charge", 026).</li>
 * </ul>
 * Each test calls the job directly. The provider is shared by the whole run, so a batch holds other classes'
 * charges too: each assertion reads only this test's reference or charge.
 */
class SettlementReconciliationIT extends AbstractReconcilingTest {

    @Autowired
    SettlementReconciliation settlement;

    String reference;

    @AfterEach
    void cleanUp() {
        FakeProvider.mode("NORMAL");
        jdbc.update("DELETE FROM payment.payment_attempt WHERE reference = ? AND state = 'UNKNOWN'", reference);
    }

    UUID attempt(String state, String token, long amount, Instant sentAt, String chargeId) {
        UUID id = UUID.randomUUID();
        var now = clock.instant().atOffset(ZoneOffset.UTC);
        jdbc.update("INSERT INTO payment.payment_attempt (id, reference, account_id, amount_minor, currency,"
                + " payment_token, state, created_at, updated_at, sent_at, idempotency_key, charge_id)"
                + " VALUES (?, ?, ?, ?, 'USD', ?, ?, ?, ?, ?, ?, ?)",
                id, reference, UUID.randomUUID(), amount, token, state, now, now, sentAt.atOffset(ZoneOffset.UTC),
                reference + ":1", chargeId);
        return id;
    }

    String stateOf(UUID attempt) {
        return jdbc.queryForObject("SELECT state FROM payment.payment_attempt WHERE id = ?", String.class, attempt);
    }

    List<String> mismatchesFor(String subject) {
        return jdbc.queryForList("SELECT kind FROM payment.settlement_mismatch WHERE subject = ? AND resolved_at IS NULL",
                String.class, subject);
    }

    void reconcile() throws Exception {
        settlement.run();
    }

    /** The decided rule: past the key window and with a lagging lookup, only the report can tell. It does. */
    @Test
    void aChargeInTheReportResolvesAnUnknownAttempt() throws Exception {
        reference = "SET-" + UUID.randomUUID();
        String token = FakeProvider.token();
        Map<String, Object> charged = FakeProvider.charge(token, 999, "USD", reference, reference + ":1");
        FakeProvider.pruneIdempotencyKeys();
        FakeProvider.mode("LOOKUP_LAG");
        UUID attempt = attempt("UNKNOWN", token, 999, clock.instant().minus(Duration.ofHours(21)), null);
        FakeProvider.closeSettlementBatch();

        reconcile();

        assertThat(stateOf(attempt)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT charge_id FROM payment.payment_attempt WHERE id = ?", String.class,
                attempt)).isEqualTo(charged.get("chargeId"));
        assertThat(mismatchesFor(attempt.toString())).isEmpty();
        assertThat(corrected("UNKNOWN", "SUCCEEDED")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aChargeTallyNeverRecordedIsAMismatchFoundOnce() throws Exception {
        reference = "SET-" + UUID.randomUUID();
        String chargeId = (String) FakeProvider.charge(FakeProvider.token(), 500, "USD", reference,
                reference + ":1").get("chargeId");
        FakeProvider.closeSettlementBatch();

        reconcile();
        reconcile();

        assertThat(mismatchesFor(chargeId)).containsExactly("CHARGED_NOT_RECORDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.settlement_line WHERE charge_id = ?", Long.class,
                chargeId)).as("each batch is imported once").isEqualTo(1);
        assertThat(meters.get("tally.payment.settlement.mismatches").tag("kind", "CHARGED_NOT_RECORDED").gauge()
                .value()).isGreaterThanOrEqualTo(1);
    }

    /** An UNKNOWN attempt and a charge with another amount: the report decides nothing, a person does. */
    @Test
    void aChargeWithAnotherAmountDecidesNothingAndIsAMismatch() throws Exception {
        reference = "SET-" + UUID.randomUUID();
        String token = FakeProvider.token();
        String chargeId = (String) FakeProvider.charge(token, 500, "USD", reference, reference + ":1").get("chargeId");
        FakeProvider.pruneIdempotencyKeys();
        FakeProvider.mode("LOOKUP_LAG");
        UUID attempt = attempt("UNKNOWN", token, 999, clock.instant().minus(Duration.ofHours(21)), null);
        FakeProvider.closeSettlementBatch();

        reconcile();

        assertThat(stateOf(attempt)).isEqualTo("UNKNOWN");
        assertThat(mismatchesFor(chargeId)).containsExactly("AMOUNT_DIFFERS");
    }

    /** The other direction: Tally recorded a success, and no batch shows the charge after the settlement delay. */
    @Test
    void aSuccessNoBatchShowsIsAMismatchOnlyAfterTheSettlementDelay() throws Exception {
        reference = "SET-" + UUID.randomUUID();
        UUID old = attempt("SUCCEEDED", "tok", 999, clock.instant().minus(Duration.ofDays(4)), "ch_never_settled");
        String recent = reference;
        reference = "SET-" + UUID.randomUUID();
        UUID young = attempt("SUCCEEDED", "tok", 999, clock.instant().minus(Duration.ofDays(1)), "ch_not_yet_settled");

        reconcile();

        assertThat(mismatchesFor(old.toString())).containsExactly("RECORDED_NOT_SETTLED");
        assertThat(mismatchesFor(young.toString())).as("may still settle").isEmpty();
        reference = recent;
    }

    /** No charge in any batch never decides an UNKNOWN attempt: it waits for a person, with an alert. */
    @Test
    void anUnknownAttemptNoBatchShowsStaysUnknownForAPerson() throws Exception {
        reference = "SET-" + UUID.randomUUID();
        UUID attempt = attempt("UNKNOWN", "tok", 999, clock.instant().minus(Duration.ofDays(4)), null);

        reconcile();

        assertThat(stateOf(attempt)).isEqualTo("UNKNOWN");
        assertThat(mismatchesFor(attempt.toString())).containsExactly("UNKNOWN_NOT_SETTLED");
        assertThat(meters.get("tally.payment.settlement.mismatches").tag("kind", "UNKNOWN_NOT_SETTLED").gauge()
                .value()).isGreaterThanOrEqualTo(1);
    }

    /** A charge that settles late closes the mismatch raised while it was missing, and decides the attempt. */
    @Test
    void aLateSettlementClosesTheMismatchAndResolvesTheAttempt() throws Exception {
        reference = "SET-" + UUID.randomUUID();
        String token = FakeProvider.token();
        FakeProvider.charge(token, 999, "USD", reference, reference + ":1");
        FakeProvider.pruneIdempotencyKeys();
        FakeProvider.mode("LOOKUP_LAG");
        UUID attempt = attempt("UNKNOWN", token, 999, clock.instant().minus(Duration.ofDays(4)), null);
        reconcile();
        assertThat(mismatchesFor(attempt.toString())).containsExactly("UNKNOWN_NOT_SETTLED");

        FakeProvider.closeSettlementBatch();
        reconcile();

        assertThat(stateOf(attempt)).isEqualTo("SUCCEEDED");
        assertThat(mismatchesFor(attempt.toString())).isEmpty();
    }
}
