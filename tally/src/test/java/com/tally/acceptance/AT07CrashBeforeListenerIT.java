package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.account.AccountApi;
import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.InvoicingApi;
import com.tally.platform.TestClock;
import com.tally.subscription.SubscriptionApi;
import com.tally.support.FakeProvider;
import com.tally.support.TallyInstances;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import spike.modulith.SpikeConfiguration;
import spike.modulith.SpikeEvent;
import spike.modulith.SpikeListener;
import spike.modulith.SpikeListener.Behaviour;

/**
 * AT-7. Crash after the invoice commit, before the listener (D2, ADR-022). Pass when: collection is
 * requested exactly once after recovery.
 *
 * <p>Two real Tally instances on one database. Instance A has a single async thread. Blocking it in
 * the right order makes A commit the invoice (and its InvoiceIssued publication) while the listener
 * that requests collection is still queued behind a blocked task; then A goes away. Instance B, with
 * its pollers and the resubmission job running, must recover the work.
 */
class AT07CrashBeforeListenerIT {

    final TallyInstances instances = new TallyInstances();

    @AfterEach
    void stop() {
        SpikeListener.RELEASE.release(100);
        instances.close();
    }

    static String collectionListenerStatus(JdbcTemplate jdbc, String reference) {
        return jdbc.queryForObject("SELECT status FROM event_publication WHERE serialized_event LIKE ?"
                + " AND event_type LIKE '%InvoiceIssued' AND listener_id LIKE '%InvoicingListeners%'",
                String.class, "%" + reference + "%");
    }

    static int attempts(JdbcTemplate jdbc, String reference) {
        return jdbc.queryForObject("SELECT count(*) FROM payment.payment_attempt WHERE reference = ?",
                Integer.class, reference);
    }

    @Test
    void collectionIsRequestedExactlyOnceAfterRecovery() {
        SpikeListener.RELEASE.drainPermits();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        SpikeListener.BEHAVIOUR.put(first, Behaviour.BLOCK);
        SpikeListener.BEHAVIOUR.put(second, Behaviour.BLOCK);
        String providerUrl = FakeProvider.baseUrl();

        ConfigurableApplicationContext a = instances.start("at07-instance-a", SpikeConfiguration.class,
                "spring.task.execution.pool.core-size=1", "spring.task.execution.pool.max-size=1",
                "tally.payment.provider.base-url=" + providerUrl);
        JdbcTemplate jdbc = a.getBean(JdbcTemplate.class);
        jdbc.update("DELETE FROM event_publication WHERE completion_date IS NULL");
        AccountApi accounts = a.getBean(AccountApi.class);
        UUID account = accounts.open(Currency.getInstance("USD"), ZoneId.of("UTC")).id();
        accounts.recordPaymentToken(account, FakeProvider.token());
        TransactionTemplate tx = a.getBean(TransactionTemplate.class);

        // Occupy A's only async thread, start the subscription, queue a second blocker, then let the first go.
        tx.executeWithoutResult(t -> a.publishEvent(new SpikeEvent(first)));
        await().atMost(Duration.ofSeconds(10)).until(() -> SpikeListener.invocations(first) == 1);
        a.getBean(SubscriptionApi.class).subscribe(account, "PREMIUM");
        tx.executeWithoutResult(t -> a.publishEvent(new SpikeEvent(second)));
        SpikeListener.RELEASE.release();

        // Invoicing issued and committed the invoice; the listener that requests collection is still queued.
        InvoicingApi invoicingA = a.getBean(InvoicingApi.class);
        await().atMost(Duration.ofSeconds(10)).until(() -> !invoicingA.invoicesFor(account).isEmpty());
        String reference = invoicingA.invoicesFor(account).getFirst().paymentReference();
        await().atMost(Duration.ofSeconds(10)).until(() -> SpikeListener.invocations(second) == 1);
        assertThat(collectionListenerStatus(jdbc, reference)).isEqualTo("PUBLISHED");
        assertThat(attempts(jdbc, reference)).isZero();

        // Instance A goes away before the listener runs.
        instances.stop(a);
        SpikeListener.BEHAVIOUR.put(first, Behaviour.SUCCEED);
        SpikeListener.BEHAVIOUR.put(second, Behaviour.SUCCEED);

        // Instance B: pollers and the resubmission job running.
        ConfigurableApplicationContext b = instances.start("at07-instance-b", SpikeConfiguration.class,
                "tally.platform.polling-enabled=true", "tally.platform.poll-interval=100ms",
                "tally.payment.provider.base-url=" + providerUrl);
        JdbcTemplate jdbcB = b.getBean(JdbcTemplate.class);
        TestClock clockB = b.getBean(TestClock.class);
        InvoicingApi invoicingB = b.getBean(InvoicingApi.class);
        jdbcB.update("UPDATE platform.jobs SET status = 'READY', claim_token = NULL, due_at = ?, last_success_at = NULL"
                + " WHERE queue_key = 'event-resubmission'", clockB.instant().atOffset(ZoneOffset.UTC));

        // Time passes on B a minute at a time; the resubmission job runs each minute.
        Instant crashedAt = clockB.instant();
        while (invoicingB.invoicesFor(account).getFirst().status() != InvoiceStatus.PAID
                && Duration.between(crashedAt, clockB.instant()).compareTo(Duration.ofMinutes(15)) < 0) {
            clockB.advance(Duration.ofMinutes(1));
            Instant now = clockB.instant();
            await().atMost(Duration.ofSeconds(10)).until(() -> {
                OffsetDateTime at = jdbcB.queryForObject(
                        "SELECT last_success_at FROM platform.jobs WHERE queue_key = 'event-resubmission'",
                        OffsetDateTime.class);
                return at != null && !at.toInstant().isBefore(now);
            });
            await().atMost(Duration.ofSeconds(10)).until(() -> attempts(jdbcB, reference) == 0
                    || invoicingB.invoicesFor(account).getFirst().status() == InvoiceStatus.PAID);
        }
        Duration recovery = Duration.between(crashedAt, clockB.instant());

        assertThat(invoicingB.invoicesFor(account).getFirst().status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(collectionListenerStatus(jdbcB, reference)).isEqualTo("COMPLETED");

        // Exactly once, also after further resubmission runs.
        clockB.advance(Duration.ofMinutes(10));
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).until(() -> attempts(jdbcB, reference) == 1);
        assertThat(FakeProvider.chargeCount(reference)).isEqualTo(1);
        System.out.printf("AT-7: collection requested once, %d min after the crash (business time)%n",
                recovery.toMinutes());
    }
}
