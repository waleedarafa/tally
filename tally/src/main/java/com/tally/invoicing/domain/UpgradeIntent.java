package com.tally.invoicing.domain;

import com.tally.offers.PriceQuote;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Remembers a mid-period upgrade between the charge and the documents. The charge uses the
 * adjustment's payment reference before that invoice exists (ADR-041), so PaymentSucceeded looks
 * this up by reference and then the upgrade is finished from that intent. Completed intents are
 * never restarted (decision 005).
 */
public final class UpgradeIntent {

    /** How an open intent ended without completing. */
    public enum Closure { DECLINED, REFUND_DUE }

    private final String paymentReference;
    private final UUID subscriptionId;
    private final UUID accountId;
    private final String newPlanId;
    private final int newOfferVersion;
    private final UUID periodInvoiceId;
    private final PriceQuote quote;
    private final Instant completedAt;
    private final Instant closedAt;
    private final Closure closure;

    private UpgradeIntent(String paymentReference, UUID subscriptionId, UUID accountId, String newPlanId,
            int newOfferVersion, UUID periodInvoiceId, PriceQuote quote, Instant completedAt,
            Instant closedAt, Closure closure) {
        this.paymentReference = Objects.requireNonNull(paymentReference, "paymentReference");
        this.subscriptionId = Objects.requireNonNull(subscriptionId, "subscriptionId");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.newPlanId = Objects.requireNonNull(newPlanId, "newPlanId");
        this.newOfferVersion = newOfferVersion;
        this.periodInvoiceId = Objects.requireNonNull(periodInvoiceId, "periodInvoiceId");
        this.quote = Objects.requireNonNull(quote, "quote");
        this.completedAt = completedAt;
        this.closedAt = closedAt;
        this.closure = closure;
    }

    public static UpgradeIntent place(String paymentReference, UUID subscriptionId, UUID accountId, String newPlanId,
            int newOfferVersion, UUID periodInvoiceId, PriceQuote quote) {
        if (newOfferVersion < 1) {
            throw new IllegalArgumentException("Offer versions are numbered from 1: " + newOfferVersion);
        }
        return new UpgradeIntent(paymentReference, subscriptionId, accountId, newPlanId, newOfferVersion,
                periodInvoiceId, quote, null, null, null);
    }

    public UpgradeIntent completed(Instant completedAt) {
        Objects.requireNonNull(completedAt, "completedAt");
        return new UpgradeIntent(paymentReference, subscriptionId, accountId, newPlanId, newOfferVersion,
                periodInvoiceId, quote, completedAt, closedAt, closure);
    }

    public UpgradeIntent closed(Closure closure, Instant closedAt) {
        Objects.requireNonNull(closure, "closure");
        Objects.requireNonNull(closedAt, "closedAt");
        return new UpgradeIntent(paymentReference, subscriptionId, accountId, newPlanId, newOfferVersion,
                periodInvoiceId, quote, completedAt, closedAt, closure);
    }

    public boolean completed() {
        return completedAt != null;
    }

    public String paymentReference() {
        return paymentReference;
    }

    public UUID subscriptionId() {
        return subscriptionId;
    }

    public UUID accountId() {
        return accountId;
    }

    public String newPlanId() {
        return newPlanId;
    }

    public int newOfferVersion() {
        return newOfferVersion;
    }

    public UUID periodInvoiceId() {
        return periodInvoiceId;
    }

    public PriceQuote quote() {
        return quote;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public Instant closedAt() {
        return closedAt;
    }

    public Closure closure() {
        return closure;
    }

    public boolean closed() {
        return closedAt != null;
    }

    public boolean open() {
        return !completed() && !closed();
    }
}
