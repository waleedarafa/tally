package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.payment.AttemptState;
import com.tally.support.AbstractReconcilingTest;
import com.tally.support.FakeProvider;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * AT-15. Crash after an attempt is saved, before the call returns (ADR-018, D2). Pass when: the attempt
 * becomes UNKNOWN after the call timeout, is resolved by asking the provider, and the customer is charged
 * at most once.
 *
 * <p>The crash is reproduced in the database, as AT-7 does: the collection request and its attempt
 * committed as SENT, the call made or not, then the instance gone; its queue item came back through the
 * reaper. A live instance must neither send the attempt again nor leave it SENT.
 *
 * <p>Every test context that polls shares the reconciliation job and these rows, each on its own clock,
 * so any of them may make the attempt stale and resolve it. The assertions read the rows and the
 * provider, never this context's counters.
 */
class AT15CrashAfterAttemptSentIT extends AbstractReconcilingTest {

    /** The rows a worker leaves when its instance dies during the provider call. */
    UUID crashedWhileSending(String reference, String token) {
        UUID request = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        var now = clock.instant().atOffset(ZoneOffset.UTC);
        jdbc.update("INSERT INTO payment.collection_request (id, reference, account_id, amount_minor, currency,"
                + " payment_token, outcome, created_at, updated_at) VALUES (?, ?, ?, 999, 'USD', ?, 'OPEN', ?, ?)",
                request, reference, account, token, now, now);
        jdbc.update("INSERT INTO payment.payment_attempt (id, request_id, reference, account_id, amount_minor,"
                + " currency, payment_token, state, created_at, updated_at, sent_at, idempotency_key)"
                + " VALUES (?, ?, ?, ?, 999, 'USD', ?, 'SENT', ?, ?, ?, ?)",
                attempt, request, reference, account, token, now, now, now, reference + ":1");
        // The reaper hands the item back: another worker picks it up.
        jdbc.update("INSERT INTO payment.collection_queue (queue_key, due_at, payload, status, attempts, created_at)"
                + " VALUES (?, ?, '', 'READY', 1, ?)", attempt.toString(), now, now);
        return attempt;
    }

    String stateOf(UUID attempt) {
        return jdbc.queryForObject("SELECT state FROM payment.payment_attempt WHERE id = ?", String.class, attempt);
    }

    void awaitItemDone(UUID attempt) {
        await().atMost(Duration.ofSeconds(10)).until(() -> "DONE".equals(jdbc.queryForObject(
                "SELECT status FROM payment.collection_queue WHERE queue_key = ?", String.class, attempt.toString())));
    }

    /** Lets the reconciliation job run past the call timeout (6 s: connect 1 s + read 5 s). */
    void timePasses() {
        clock.advance(Duration.ofMinutes(1));
    }

    @Test
    void theProviderChargedBeforeTheCrashSoTheAttemptResolvesAsSucceededAndIsNeverSentAgain() {
        String reference = "AT15-" + UUID.randomUUID();
        String token = FakeProvider.token();
        UUID attempt = crashedWhileSending(reference, token);
        // The call reached the provider and charged; its answer died with the instance.
        FakeProvider.charge(token, 999, "USD", reference, reference + ":1");

        awaitItemDone(attempt);
        assertThat(FakeProvider.requestCount(reference)).as("not sent again").isEqualTo(1);

        timePasses();
        await().atMost(Duration.ofSeconds(10)).until(() -> "SUCCEEDED".equals(stateOf(attempt)));
        System.out.println("AT-15 charged before crash: " + stateOf(attempt) + ", provider requests "
                + FakeProvider.requestCount(reference) + ", charges " + FakeProvider.chargeCount(reference));
        assertThat(payments.status(reference).orElseThrow().state()).isEqualTo(AttemptState.SUCCEEDED);
        assertThat(FakeProvider.requestCount(reference)).as("never sent again").isEqualTo(1);
        assertThat(FakeProvider.chargeCount(reference)).isEqualTo(1);
    }

    /**
     * The lookup finds nothing, and the attempt's key is inside the window: reconciliation repeats the
     * charge with the same key (proposed ADR-026 b). The first request never executed, so the repeat
     * charges now, once, and resolves the same attempt: no new attempt is made.
     */
    @Test
    void theCallNeverReachedTheProviderSoTheRepeatWithItsKeyChargesOnce() {
        String reference = "AT15-" + UUID.randomUUID();
        UUID attempt = crashedWhileSending(reference, FakeProvider.token());

        awaitItemDone(attempt);
        timePasses();

        await().atMost(Duration.ofSeconds(15)).until(() ->
                payments.status(reference).map(s -> s.state() == AttemptState.SUCCEEDED).orElse(false));
        System.out.println("AT-15 crash before the call: attempt " + stateOf(attempt) + ", charges "
                + FakeProvider.chargeCount(reference));
        assertThat(stateOf(attempt)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.payment_attempt WHERE reference = ?",
                Long.class, reference)).as("no new attempt").isEqualTo(1);
        assertThat(FakeProvider.chargeCount(reference)).isEqualTo(1);
    }
}
