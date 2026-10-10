package com.tally.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.tally.shared.Money;
import com.tally.support.AbstractProviderIntegrationTest;
import com.tally.support.FakeProvider;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Collections' retry of a declined reference (Slice 1R PR-8; PR #13 review; proposed ADR-026). A new
 * attempt for the same reference, with its own key, only when none is open and none succeeded; but first
 * Payment looks the reference up, and a charge found corrects the decline instead. The retry number is the
 * guard: a retry asked for twice makes one attempt.
 */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms",
        "tally.test.context=collect-again"
})
class CollectAgainIT extends AbstractProviderIntegrationTest {

    @Autowired
    PaymentApi payments;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    String declined(String mode) {
        String reference = "AGAIN-" + UUID.randomUUID();
        FakeProvider.mode(mode);
        payments.requestCollection(reference, UUID.randomUUID(), Money.of(999, "USD"), FakeProvider.token());
        awaitLatest(reference, AttemptState.DECLINED);
        FakeProvider.mode("NORMAL");
        // Keeps reconciliation's 48-hour re-check of the decline out of the way: the retry looks up itself.
        jdbc.update("UPDATE payment.payment_attempt SET reconcile_at = NULL WHERE reference = ?", reference);
        return reference;
    }

    void awaitLatest(String reference, AttemptState state) {
        await().atMost(Duration.ofSeconds(10)).until(() ->
                payments.status(reference).map(s -> s.state() == state).orElse(false));
    }

    List<String> attempts(String reference) {
        return jdbc.queryForList("SELECT idempotency_key || '=' || state FROM payment.payment_attempt"
                + " WHERE reference = ? ORDER BY number", String.class, reference);
    }

    @Test
    void afterADeclineARetryIsANewAttemptWithItsOwnKey() {
        String reference = declined("DECLINE_HARD(100)");

        payments.collectAgain(reference, 1);

        awaitLatest(reference, AttemptState.SUCCEEDED);
        assertThat(attempts(reference)).containsExactly(reference + ":1=DECLINED", reference + ":2=SUCCEEDED");
        assertThat(FakeProvider.chargeCount(reference)).as("the provider records the decline and the charge").isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT outcome FROM payment.collection_request WHERE reference = ?",
                String.class, reference)).isEqualTo("SUCCEEDED");
    }

    @Test
    void theSameRetryAskedForTwiceMakesOneAttempt() {
        String reference = declined("DECLINE_HARD(100)");

        payments.collectAgain(reference, 1);
        awaitLatest(reference, AttemptState.SUCCEEDED);
        payments.collectAgain(reference, 1);

        assertThat(attempts(reference)).hasSize(2);
    }

    /**
     * Proposed ADR-026: the provider reported a decline, but charged (FR-018). The retry's lookup finds the
     * charge: no new attempt, and the decline is corrected to SUCCEEDED.
     */
    @Test
    void aChargeFoundBeforeTheRetryCorrectsTheDeclineAndMakesNoNewAttempt() {
        String reference = declined("LIE");

        payments.collectAgain(reference, 1);

        assertThat(attempts(reference)).containsExactly(reference + ":1=SUCCEEDED");
        assertThat(FakeProvider.chargeCount(reference)).isEqualTo(1);
    }

    @Test
    void nothingIsRetriedWhileAnAttemptIsOpenOrAfterOneSucceeded() {
        String reference = "AGAIN-" + UUID.randomUUID();
        payments.requestCollection(reference, UUID.randomUUID(), Money.of(999, "USD"), FakeProvider.token());
        awaitLatest(reference, AttemptState.SUCCEEDED);

        payments.collectAgain(reference, 1);
        payments.collectAgain("AGAIN-NEVER-REQUESTED-" + UUID.randomUUID(), 1);

        assertThat(attempts(reference)).containsExactly(reference + ":1=SUCCEEDED");
    }

    /** CLAUDE.md rule 6: the retry asks the provider, so it refuses to run inside a transaction. */
    @Test
    void aRetryIsRefusedInsideATransaction() {
        String reference = declined("DECLINE_HARD(100)");

        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> payments.collectAgain(reference, 1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(attempts(reference)).hasSize(1);
    }
}
