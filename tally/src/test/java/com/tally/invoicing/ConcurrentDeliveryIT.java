package com.tally.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.AbstractIntegrationTest;
import com.tally.payment.PaymentSucceeded;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import com.tally.shared.Money;

/**
 * Spike A finding 3: a publication can be delivered twice at the same time. Two concurrent
 * deliveries of one PaymentSucceeded must still pay the invoice once and publish one InvoicePaid.
 */
class ConcurrentDeliveryIT extends AbstractIntegrationTest {

    static class CommittedInvoicePaid {
        private final List<InvoicePaid> events = new CopyOnWriteArrayList<>();

        @TransactionalEventListener
        void on(InvoicePaid paid) {
            events.add(paid);
        }

        // Read through a method: the bean is proxied, so its fields are not the proxy's.
        long count(String reference) {
            return events.stream().filter(e -> e.paymentReference().equals(reference)).count();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Capture {
        @Bean
        CommittedInvoicePaid committedInvoicePaid() {
            return new CommittedInvoicePaid();
        }
    }

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CommittedInvoicePaid committed;

    String pendingInvoice() {
        UUID id = UUID.randomUUID();
        String reference = "INV-" + id;
        jdbc.update("INSERT INTO invoicing.invoice (id, account_id, subscription_id, plan_id, status, amount_minor,"
                        + " currency, period_start, period_end, payment_reference, issued_at)"
                        + " VALUES (?, ?, ?, 'BASIC', 'PAYMENT_PENDING', 999, 'USD', ?, ?, ?, ?)",
                id, UUID.randomUUID(), UUID.randomUUID(), LocalDate.parse("2026-01-15"), LocalDate.parse("2026-02-15"),
                reference, clock.instant().atOffset(ZoneOffset.UTC));
        return reference;
    }

    @Test
    void twoConcurrentDeliveriesPayTheInvoiceOnce() throws Exception {
        for (int round = 0; round < 20; round++) {
            String reference = pendingInvoice();
            PaymentSucceeded succeeded = new PaymentSucceeded(UUID.randomUUID(), clock.instant(), UUID.randomUUID(),
                    reference, Money.of(999, "USD"), "ch_" + round);
            CyclicBarrier together = new CyclicBarrier(2);
            Thread a = Thread.ofPlatform().start(() -> deliver(together, succeeded));
            Thread b = Thread.ofPlatform().start(() -> deliver(together, succeeded));
            a.join();
            b.join();

            await().atMost(Duration.ofSeconds(10)).until(() -> "PAID".equals(jdbc.queryForObject(
                    "SELECT status FROM invoicing.invoice WHERE payment_reference = ?", String.class, reference)));
            await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5))
                    .until(() -> committed.count(reference) == 1);
        }
    }

    private void deliver(CyclicBarrier together, PaymentSucceeded event) {
        try {
            together.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        transactions.executeWithoutResult(tx -> events.publishEvent(event));
    }
}
