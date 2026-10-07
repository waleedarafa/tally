package com.tally.invoicing.application;

import com.tally.account.AccountApi;
import com.tally.invoicing.domain.BillingCalendar;
import com.tally.invoicing.domain.SubscriptionBilling;
import com.tally.invoicing.domain.SubscriptionBillingRepository;
import com.tally.platform.DurableQueue;
import com.tally.platform.DurableQueues;
import com.tally.platform.Outcome;
import com.tally.platform.QueueDefinition;
import com.tally.platform.QueueHandler;
import com.tally.platform.QueueItem;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The billing schedule (ADR-002, ADR-017): one queue item per account and billing date, due at local
 * midnight. The key is the D1 business key, so a billing date can be scheduled only once. Each
 * renewal issues its period's invoice and schedules the next billing date in one transaction.
 */
@Component
@EnableConfigurationProperties(BillingProperties.class)
class BillingSchedule implements QueueHandler {

    private static final Logger log = LoggerFactory.getLogger(BillingSchedule.class);

    static final QueueDefinition DEFINITION = QueueDefinition.of("billing-schedule", "invoicing.billing_schedule");

    private final DurableQueue queue;
    private final SubscriptionBillingRepository subscriptions;
    private final AccountApi accounts;
    private final InvoiceIssuer issuer;
    private final TransactionTemplate transactions;

    BillingSchedule(DurableQueues queues, BillingProperties properties, SubscriptionBillingRepository subscriptions,
            AccountApi accounts, InvoiceIssuer issuer, TransactionTemplate transactions) {
        this.subscriptions = subscriptions;
        this.accounts = accounts;
        this.issuer = issuer;
        this.transactions = transactions;
        // Pollers start only once the application is up, so registering here is safe.
        this.queue = queues.register(DEFINITION.withWorkers(properties.billingWorkers()), this);
    }

    /** Schedules billing for {@code billingDate} at local midnight. Idempotent per account and date. */
    void schedule(UUID accountId, UUID subscriptionId, LocalDate billingDate, ZoneId zone) {
        queue.enqueue(accountId + ":" + billingDate, BillingCalendar.billingTime(billingDate, zone),
                subscriptionId + "," + billingDate);
    }

    @Override
    public Outcome handle(QueueItem item) {
        String[] payload = item.payload().split(",");
        UUID subscriptionId = UUID.fromString(payload[0]);
        LocalDate billingDate = LocalDate.parse(payload[1]);
        try {
            transactions.executeWithoutResult(tx -> renew(subscriptionId, billingDate));
        } catch (DataIntegrityViolationException e) {
            // Another delivery of this item issued the invoice first (D1's unique constraint won).
            log.info("Billing {} for subscription {} was already issued", billingDate, subscriptionId);
        }
        return Outcome.done();
    }

    private void renew(UUID subscriptionId, LocalDate billingDate) {
        SubscriptionBilling subscription = subscriptions.findById(subscriptionId).orElseThrow();
        ZoneId zone = accounts.get(subscription.accountId()).orElseThrow().timeZone();
        LocalDate periodEnd = subscription.calendar().nextBillingDate(billingDate);
        issuer.issue(subscription.accountId(), subscriptionId, subscription.planId(), billingDate, periodEnd);
        schedule(subscription.accountId(), subscriptionId, periodEnd, zone);
    }
}
