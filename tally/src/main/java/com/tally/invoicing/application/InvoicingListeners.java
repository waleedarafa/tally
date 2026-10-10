package com.tally.invoicing.application;

import com.tally.account.AccountApi;
import com.tally.account.AccountView;
import com.tally.invoicing.InvoiceIssued;
import com.tally.invoicing.InvoicePaid;
import com.tally.invoicing.domain.Invoice;
import com.tally.invoicing.domain.Invoices;
import com.tally.invoicing.domain.SubscriptionBilling;
import com.tally.invoicing.domain.SubscriptionBillings;
import com.tally.payment.FailureReason;
import com.tally.payment.PaymentApi;
import com.tally.payment.PaymentFailed;
import com.tally.payment.PaymentSucceeded;
import com.tally.subscription.SubscriptionChangeType;
import com.tally.subscription.SubscriptionChanged;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Invoicing's reactions to other modules' events. Each runs after the publisher commits, in its own
 * transaction, and is idempotent (FR-009, FR-022): a resubmitted event changes nothing twice.
 */
@Component
class InvoicingListeners {

    private static final Logger log = LoggerFactory.getLogger(InvoicingListeners.class);

    private final Invoices invoices;
    private final SubscriptionBillings subscriptions;
    private final InvoiceIssuer issuer;
    private final BillingSchedule schedule;
    private final AccountApi accounts;
    private final PaymentApi payments;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    InvoicingListeners(Invoices invoices, SubscriptionBillings subscriptions, InvoiceIssuer issuer,
            BillingSchedule schedule, AccountApi accounts, PaymentApi payments, ApplicationEventPublisher events,
            Clock clock) {
        this.invoices = invoices;
        this.subscriptions = subscriptions;
        this.issuer = issuer;
        this.schedule = schedule;
        this.accounts = accounts;
        this.payments = payments;
        this.events = events;
        this.clock = clock;
    }

    /**
     * First invoice, at subscribe: one period from the local day the subscription starts, no
     * proration, no tax (brief section 1). Records the billing calendar and schedules the first
     * renewal in the same transaction.
     */
    @ApplicationModuleListener
    void on(SubscriptionChanged changed) {
        if (changed.type() != SubscriptionChangeType.STARTED) {
            return;
        }
        AccountView account = accounts.get(changed.accountId()).orElseThrow();
        LocalDate startedOn = LocalDate.ofInstant(changed.occurredAt(), account.timeZone());
        // Atomic: a duplicate delivery, even a concurrent one, finds the record and changes nothing (FR-028).
        subscriptions.insertIfAbsent(
                new SubscriptionBilling(changed.subscriptionId(), changed.accountId(), changed.planId(), startedOn));
        SubscriptionBilling billing = subscriptions.findById(changed.subscriptionId()).orElseThrow();
        LocalDate periodEnd = billing.calendar().nextBillingDate(startedOn);
        // The first invoice is due when the subscription starts.
        issuer.issue(changed.accountId(), changed.subscriptionId(), changed.planId(), startedOn, periodEnd,
                changed.occurredAt());
        schedule.schedule(changed.accountId(), changed.subscriptionId(), periodEnd, account.timeZone());
    }

    /** Every committed InvoiceIssued leads to exactly one collection request (D2). Payment is idempotent per reference. */
    @ApplicationModuleListener
    void on(InvoiceIssued issued) {
        String token = accounts.get(issued.accountId()).flatMap(AccountView::paymentToken)
                .orElseThrow(() -> new IllegalStateException("Account " + issued.accountId() + " has no payment token"));
        payments.requestCollection(issued.paymentReference(), issued.accountId(), issued.amount(), token);
    }

    @ApplicationModuleListener
    void on(PaymentSucceeded succeeded) {
        invoices.findByPaymentReference(succeeded.reference()).ifPresentOrElse(invoice -> {
            if (invoice.paid(clock.instant())) {
                events.publishEvent(new InvoicePaid(UUID.randomUUID(), clock.instant(), invoice.accountId(),
                        invoice.id(), invoice.paymentReference()));
            }
        }, () -> unknownReference(succeeded.reference()));
    }

    @ApplicationModuleListener
    void on(PaymentFailed failed) {
        // Invoicing acts only on resolved outcomes (FR-018).
        if (failed.reason() == FailureReason.UNKNOWN) {
            return;
        }
        invoices.findByPaymentReference(failed.reference())
                .ifPresentOrElse(Invoice::paymentFailed, () -> unknownReference(failed.reference()));
    }

    /** Payment is shared by design: a reference may not be an invoice of ours (FR-014). */
    private static void unknownReference(String reference) {
        log.warn("Payment result for unknown reference {} ignored", reference);
    }
}
