package com.tally.invoicing.application;

import com.tally.account.AccountApi;
import com.tally.account.AccountView;
import com.tally.invoicing.InvoiceIssued;
import com.tally.invoicing.InvoicePaid;
import com.tally.invoicing.InvoicePaymentDeclined;
import com.tally.invoicing.domain.Invoice;
import com.tally.invoicing.domain.Invoices;
import com.tally.payment.FailureReason;
import com.tally.payment.PaymentApi;
import com.tally.payment.PaymentFailed;
import com.tally.payment.PaymentSucceeded;
import com.tally.subscription.SubscriptionChangeType;
import com.tally.subscription.SubscriptionChanged;
import java.time.Clock;
import java.time.Instant;
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
    private final InvoiceIssuer issuer;
    private final AccountApi accounts;
    private final PaymentApi payments;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    InvoicingListeners(Invoices invoices, InvoiceIssuer issuer, AccountApi accounts, PaymentApi payments,
            ApplicationEventPublisher events, Clock clock) {
        this.invoices = invoices;
        this.issuer = issuer;
        this.accounts = accounts;
        this.payments = payments;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Bills the subscription's current period when it starts and each time it renews (Design Record §12):
     * the event carries the period and the offer version. The first invoice is due when the subscription
     * starts; a renewal's at local midnight of the period's first day (D6). Idempotent: the invoice ID is
     * the subscription and period number, so a duplicate delivery, even a concurrent one, adds nothing.
     */
    @ApplicationModuleListener
    void on(SubscriptionChanged changed) {
        boolean billable = changed.type() == SubscriptionChangeType.STARTED
                || changed.type() == SubscriptionChangeType.PERIOD_RENEWED;
        if (!billable) {
            return;
        }
        if (changed.periodNumber() == null) {
            // Published by a release before Slice 1R PR-5, which billed through Invoicing's own schedule.
            log.warn("Subscription {} change without a period ignored", changed.subscriptionId());
            return;
        }
        AccountView account = accounts.get(changed.accountId()).orElseThrow();
        Instant billedFor = changed.type() == SubscriptionChangeType.STARTED ? changed.occurredAt()
                : changed.periodStart().atStartOfDay(account.timeZone()).toInstant();
        issuer.issue(changed.accountId(), changed.subscriptionId(), changed.planId(), changed.offerVersion(),
                changed.periodNumber(), changed.periodStart(), changed.periodEnd(), billedFor);
    }

    /**
     * Every committed InvoiceIssued with something to pay leads to exactly one collection request (D2).
     * Payment is idempotent per reference. A zero invoice is paid when issued and never reaches Payment.
     */
    @ApplicationModuleListener
    void on(InvoiceIssued issued) {
        if (issued.amount().minorUnits() == 0) {
            return;
        }
        String token = accounts.get(issued.accountId()).flatMap(AccountView::paymentToken)
                .orElseThrow(() -> new IllegalStateException("Account " + issued.accountId() + " has no payment token"));
        payments.requestCollection(issued.paymentReference(), issued.accountId(), issued.amount(), token);
    }

    @ApplicationModuleListener
    void on(PaymentSucceeded succeeded) {
        invoices.forPaymentReference(succeeded.reference()).ifPresentOrElse(invoice -> {
            if (invoice.paid(clock.instant())) {
                invoices.update(invoice);
                events.publishEvent(new InvoicePaid(UUID.randomUUID(), clock.instant(), invoice.accountId(),
                        invoice.id(), invoice.paymentReference(), invoice.version()));
            }
        }, () -> unknownReference(succeeded.reference()));
    }

    @ApplicationModuleListener
    void on(PaymentFailed failed) {
        // Invoicing acts only on resolved outcomes (FR-018).
        if (failed.reason() == FailureReason.UNKNOWN) {
            return;
        }
        invoices.forPaymentReference(failed.reference()).ifPresentOrElse(invoice -> {
            if (invoice.paymentFailed()) {
                invoices.update(invoice);
                events.publishEvent(new InvoicePaymentDeclined(UUID.randomUUID(), clock.instant(),
                        invoice.accountId(), invoice.id(), invoice.paymentReference(), invoice.amount(),
                        invoice.version()));
            }
        }, () -> unknownReference(failed.reference()));
    }

    /** Payment is shared by design: a reference may not be an invoice of ours (FR-014). */
    private static void unknownReference(String reference) {
        log.warn("Payment result for unknown reference {} ignored", reference);
    }
}
