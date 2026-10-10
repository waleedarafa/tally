package com.tally.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.account.AccountApi;
import com.tally.payment.AttemptState;
import com.tally.payment.PaymentApi;
import com.tally.shared.Money;
import com.tally.support.AbstractProviderIntegrationTest;
import com.tally.support.FakeProvider;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Currency;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FR-041: no duplicate delivery of {@code InvoiceIssued}, at any time, leads to a second charge.
 * Each case must leave exactly one collection request, one attempt and one provider charge, and
 * no delivery may fail: a failed duplicate would be resubmitted, and alarm someone, for nothing.
 */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms",
        "tally.test.context=duplicate-collection"
})
class DuplicateCollectionRequestIT extends AbstractProviderIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AccountApi accounts;

    @Autowired
    PaymentApi payments;

    // Test threads only, standing in for a second instance; never production code.
    final ExecutorService otherInstance = Executors.newSingleThreadExecutor();

    @AfterEach
    void stop() {
        otherInstance.shutdownNow();
    }

    UUID accountWithCard() {
        UUID account = accounts.open(Currency.getInstance("USD"), ZoneId.of("UTC")).id();
        accounts.recordPaymentToken(account, FakeProvider.token());
        return account;
    }

    InvoiceIssued issued(UUID account, String reference) {
        return new InvoiceIssued(UUID.randomUUID(), clock.instant(), account, UUID.randomUUID(), reference,
                Money.of(999, "USD"), 1L);
    }

    long count(String table, String reference) {
        return jdbc.queryForObject("SELECT count(*) FROM payment." + table + " WHERE reference = ?", Long.class,
                reference);
    }

    void awaitDelivered(String reference) {
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM event_publication WHERE serialized_event LIKE ? AND completion_date IS NULL",
                Long.class, "%" + reference + "%") == 0);
    }

    void awaitCharged(String reference) {
        await().atMost(Duration.ofSeconds(20)).until(() -> payments.status(reference)
                .map(s -> s.state() == AttemptState.SUCCEEDED).orElse(false));
    }

    void assertCollectedOnce(String reference) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE serialized_event LIKE ?"
                + " AND status = 'FAILED'", Long.class, "%" + reference + "%"))
                .as("failed deliveries for %s", reference).isZero();
        assertThat(count("collection_request", reference)).as("requests for %s", reference).isEqualTo(1);
        assertThat(count("payment_attempt", reference)).as("attempts for %s", reference).isEqualTo(1);
        assertThat(FakeProvider.chargeCount(reference)).as("provider charges for %s", reference).isEqualTo(1);
    }

    @Test
    void twoDeliveriesAtTheSameMomentAndALateOneCollectOnce() {
        for (int round = 0; round < 5; round++) {
            UUID account = accountWithCard();
            String reference = "DUP-" + UUID.randomUUID();

            // Two publications in one transaction: both listeners start together on the listener pool.
            transactions.executeWithoutResult(tx -> {
                context.publishEvent(issued(account, reference));
                context.publishEvent(issued(account, reference));
            });
            awaitDelivered(reference);
            awaitCharged(reference);
            assertCollectedOnce(reference);

            // Again, after the first attempt has finished.
            transactions.executeWithoutResult(tx -> context.publishEvent(issued(account, reference)));
            awaitDelivered(reference);
            assertCollectedOnce(reference);
            assertThat(jdbc.queryForObject("SELECT outcome FROM payment.collection_request WHERE reference = ?",
                    String.class, reference)).isEqualTo("SUCCEEDED");
        }
    }

    /** The overlap made deterministic: B waits on A's uncommitted insert, then creates nothing. */
    @Test
    void aDuplicateWaitsForTheFirstRequestToCommitThenCreatesNothing() throws Exception {
        UUID account = accountWithCard();
        String reference = "OVERLAP-" + UUID.randomUUID();
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(tx -> {
            payments.requestCollection(reference, account, Money.of(999, "USD"), FakeProvider.token());
            firstInserted.countDown();
            await().atMost(Duration.ofSeconds(20)).until(() -> commitFirst.getCount() == 0);
        }), otherInstance);
        assertThat(firstInserted.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> second = CompletableFuture.runAsync(
                () -> payments.requestCollection(reference, account, Money.of(999, "USD"), FakeProvider.token()));
        Thread.sleep(500);
        assertThat(second).as("the duplicate waits for the first insert to commit").isNotDone();

        commitFirst.countDown();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS); // completes normally: no exception
        awaitCharged(reference);
        assertCollectedOnce(reference);
    }
}
