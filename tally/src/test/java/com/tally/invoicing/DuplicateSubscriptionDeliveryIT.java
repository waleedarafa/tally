package com.tally.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.AbstractIntegrationTest;
import com.tally.account.AccountApi;
import com.tally.subscription.SubscriptionChangeType;
import com.tally.subscription.SubscriptionChanged;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FR-028: Invoicing's SubscriptionChanged listener absorbs duplicate deliveries, at the same moment
 * or later. Every delivery completes (none fails, so none is resubmitted or alarms anyone), and the
 * subscription leaves one billing record, one first invoice and one scheduled renewal.
 */
class DuplicateSubscriptionDeliveryIT extends AbstractIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AccountApi accounts;

    /**
     * The same subscription start, as a separate publication. Each carries its own event ID: two rows
     * with identical content confuse the registry's completion marking, which a real duplicate
     * (a resubmission re-runs the same row) never produces.
     */
    SubscriptionChanged started(UUID account, UUID subscription) {
        return new SubscriptionChanged(UUID.randomUUID(), clock.instant(), account, subscription, "BASIC", 1,
                SubscriptionChangeType.STARTED);
    }

    long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    void awaitDeliveriesFinished(UUID subscription) {
        await().atMost(Duration.ofSeconds(20)).until(() -> count("SELECT count(*) FROM event_publication"
                + " WHERE serialized_event LIKE ? AND completion_date IS NULL AND status IS DISTINCT FROM 'FAILED'",
                "%" + subscription + "%") == 0);
    }

    void assertRecordedOnce(UUID account, UUID subscription) {
        assertThat(count("SELECT count(*) FROM event_publication WHERE serialized_event LIKE ? AND status = 'FAILED'",
                "%" + subscription + "%")).as("failed deliveries").isZero();
        assertThat(count("SELECT count(*) FROM event_publication WHERE serialized_event LIKE ?"
                + " AND completion_date IS NULL", "%" + subscription + "%")).as("incomplete deliveries").isZero();
        assertThat(count("SELECT count(*) FROM invoicing.subscription_billing WHERE subscription_id = ?", subscription))
                .as("billing records").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM invoicing.invoice WHERE subscription_id = ?", subscription))
                .as("first invoices").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM invoicing.billing_schedule WHERE queue_key LIKE ?", account + ":%"))
                .as("scheduled renewals").isEqualTo(1);
    }

    @Test
    void duplicateDeliveriesAtTheSameMomentAndLaterAreAbsorbed() {
        for (int round = 0; round < 10; round++) {
            UUID account = accounts.open(Currency.getInstance("USD"), ZoneId.of("UTC")).id();
            accounts.recordPaymentToken(account, "tok-duplicates");
            UUID subscription = UUID.randomUUID();

            // Two publications in one transaction: both listeners start together on the listener pool.
            transactions.executeWithoutResult(tx -> {
                context.publishEvent(started(account, subscription));
                context.publishEvent(started(account, subscription));
            });
            awaitDeliveriesFinished(subscription);
            assertRecordedOnce(account, subscription);

            transactions.executeWithoutResult(tx -> context.publishEvent(started(account, subscription)));
            awaitDeliveriesFinished(subscription);
            assertRecordedOnce(account, subscription);
        }
    }
}
