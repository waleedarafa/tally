package com.tally.checkout.application;

import com.tally.account.AccountApi;
import com.tally.invoicing.InvoiceView;
import com.tally.invoicing.InvoicingApi;
import com.tally.payment.AttemptState;
import com.tally.payment.PaymentApi;
import com.tally.payment.PaymentStatus;
import com.tally.subscription.SubscriptionApi;
import com.tally.subscription.SubscriptionView;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Checkout (FR-019): record the token, start the subscription, then wait briefly for the first
 * payment's result. It lives in its own process module because it uses Account, Subscription,
 * Invoicing and Payment, which no business module may all depend on.
 * Not transactional: the subscription must commit so the event flow can run while checkout waits.
 */
@Service
@EnableConfigurationProperties(CheckoutProperties.class)
public class Checkout {

    public enum Result {
        SUCCEEDED, FAILED, PROCESSING
    }

    /** {@code invoiceId} and {@code paymentReference} are empty if the invoice was not yet issued. */
    public record Outcome(UUID subscriptionId, Optional<UUID> invoiceId, Optional<String> paymentReference,
            Result result) {
    }

    private static final long POLL_MILLIS = 50;

    private final AccountApi accounts;
    private final SubscriptionApi subscriptions;
    private final PaymentApi payments;
    private final InvoicingApi invoices;
    private final CheckoutProperties properties;

    Checkout(AccountApi accounts, SubscriptionApi subscriptions, PaymentApi payments, InvoicingApi invoices,
            CheckoutProperties properties) {
        this.accounts = accounts;
        this.subscriptions = subscriptions;
        this.payments = payments;
        this.invoices = invoices;
        this.properties = properties;
    }

    public Outcome checkout(UUID accountId, String planId, String paymentToken) {
        accounts.recordPaymentToken(accountId, paymentToken);
        SubscriptionView subscription = subscriptions.subscribe(accountId, planId);

        // A wait on elapsed time, not business time: the injected clock may be frozen in tests.
        long deadline = System.nanoTime() + properties.paymentWait().toNanos();
        Optional<InvoiceView> invoice = Optional.empty();
        while (true) {
            invoice = invoice.isPresent() ? invoice : invoices.invoicesForSubscription(subscription.id()).stream().findFirst();
            Optional<AttemptState> state = invoice.flatMap(i -> payments.status(i.paymentReference()))
                    .map(PaymentStatus::state);
            if (state.isPresent() && state.get() == AttemptState.SUCCEEDED) {
                return outcome(subscription, invoice, Result.SUCCEEDED);
            }
            if (state.isPresent() && state.get() == AttemptState.FAILED) {
                return outcome(subscription, invoice, Result.FAILED);
            }
            if (System.nanoTime() >= deadline) {
                return outcome(subscription, invoice, Result.PROCESSING);
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return outcome(subscription, invoice, Result.PROCESSING);
            }
        }
    }

    private static Outcome outcome(SubscriptionView subscription, Optional<InvoiceView> invoice, Result result) {
        return new Outcome(subscription.id(), invoice.map(InvoiceView::id), invoice.map(InvoiceView::paymentReference), result);
    }
}
