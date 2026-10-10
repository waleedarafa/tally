package com.tally.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.shared.Money;
import com.tally.support.AbstractProviderIntegrationTest;
import com.tally.support.FakeProvider;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** FR-005: workers claim attempts, call the provider with no transaction open, record the result. */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms"
})
class PaymentCollectionIT extends AbstractProviderIntegrationTest {

    static class Captured {
        final List<Object> events = new CopyOnWriteArrayList<>();

        @EventListener
        void on(PaymentSucceeded event) {
            events.add(event);
        }

        @EventListener
        void on(PaymentFailed event) {
            events.add(event);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Capture {
        @Bean
        Captured captured() {
            return new Captured();
        }
    }

    @Autowired
    PaymentApi payments;

    @Autowired
    Captured captured;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clear() {
        captured.events.clear();
    }

    static String reference() {
        return "TEST-" + UUID.randomUUID();
    }

    AttemptState awaitFinal(String reference) {
        await().atMost(Duration.ofSeconds(10)).until(() -> payments.status(reference)
                .map(s -> s.state() != AttemptState.REQUESTED && s.state() != AttemptState.SENT).orElse(false));
        return payments.status(reference).orElseThrow().state();
    }

    @Test
    void aCollectionIsChargedOnceAndSucceeds() {
        String reference = reference();
        UUID account = UUID.randomUUID();

        payments.requestCollection(reference, account, Money.of(999, "USD"), FakeProvider.token());

        assertThat(awaitFinal(reference)).isEqualTo(AttemptState.SUCCEEDED);
        assertThat(FakeProvider.chargeCount(reference)).isEqualTo(1);
        assertThat(captured.events).singleElement().isInstanceOfSatisfying(PaymentSucceeded.class, e -> {
            assertThat(e.reference()).isEqualTo(reference);
            assertThat(e.accountId()).isEqualTo(account);
            assertThat(e.amount()).isEqualTo(Money.of(999, "USD"));
            assertThat(e.chargeId()).startsWith("ch_");
        });
    }

    @Test
    void aSecondRequestForTheSameReferenceChangesNothing() {
        String reference = reference();
        String token = FakeProvider.token();

        payments.requestCollection(reference, UUID.randomUUID(), Money.of(999, "USD"), token);
        payments.requestCollection(reference, UUID.randomUUID(), Money.of(999, "USD"), token);

        assertThat(awaitFinal(reference)).isEqualTo(AttemptState.SUCCEEDED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.payment_attempt WHERE reference = ?",
                Integer.class, reference)).isEqualTo(1);
        assertThat(FakeProvider.chargeCount(reference)).isEqualTo(1);
    }

    @Test
    void aDeclineIsRecordedAndPublished() {
        String reference = reference();

        payments.requestCollection(reference, UUID.randomUUID(), Money.of(3699, "AED"), "tok_not_issued");

        assertThat(awaitFinal(reference)).isEqualTo(AttemptState.DECLINED);
        assertThat(payments.status(reference).orElseThrow().failureReason()).contains(FailureReason.HARD);
        assertThat(captured.events).singleElement().isInstanceOfSatisfying(PaymentFailed.class,
                e -> assertThat(e.reason()).isEqualTo(FailureReason.HARD));
    }

    @Test
    void anAttemptAlreadySentIsNeverSentAgain() {
        // A worker committed the attempt as SENT, then its instance died during the provider call.
        String reference = reference();
        UUID attempt = UUID.randomUUID();
        var now = clock.instant().atOffset(ZoneOffset.UTC);
        jdbc.update("INSERT INTO payment.payment_attempt (id, reference, account_id, amount_minor, currency,"
                + " payment_token, state, created_at, updated_at, sent_at)"
                + " VALUES (?, ?, ?, 999, 'USD', ?, 'SENT', ?, ?, ?)",
                attempt, reference, UUID.randomUUID(), FakeProvider.token(), now, now, now);
        // The reaper returned its queue item, so another worker picks it up.
        jdbc.update("INSERT INTO payment.collection_queue (queue_key, due_at, payload, status, attempts, created_at)"
                + " VALUES (?, ?, '', 'READY', 1, ?)", attempt.toString(), now, now);

        await().atMost(Duration.ofSeconds(10)).until(() -> "DONE".equals(jdbc.queryForObject(
                "SELECT status FROM payment.collection_queue WHERE queue_key = ?", String.class, attempt.toString())));
        // Not sent again: the stale rule makes it UNKNOWN after the call timeout (ADR-018; AT-15).
        assertThat(payments.status(reference).orElseThrow().state()).isEqualTo(AttemptState.SENT);
        assertThat(FakeProvider.requestCount(reference)).isZero();
        assertThat(captured.events).isEmpty();
    }
}
