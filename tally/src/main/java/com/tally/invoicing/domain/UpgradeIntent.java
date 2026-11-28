package com.tally.invoicing.domain;

import com.tally.offers.PriceLine;
import com.tally.offers.PriceQuote;
import com.tally.shared.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Remembers a mid-period upgrade between the charge and the documents. The charge uses the
 * adjustment's payment reference before that invoice exists (ADR-041), so PaymentSucceeded looks
 * this up by reference and then the upgrade is finished from that intent. Completed intents are
 * never restarted (ADR-041).
 *
 * <p>The credit a round is allowed to reverse is capped at what is left on the invoice being left
 * (net credit and its VAT included). The cap is decided when the intent is placed, before the
 * charge, and stored here ({@code credit_cap_minor}, ADR-060): a retry or a race loser charges the
 * same amount and the credit note issued after the charge fits.
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
    private final TaxQuote tax;
    private final Long creditCapMinor;
    private final Instant completedAt;
    private final Instant closedAt;
    private final Closure closure;

    private UpgradeIntent(String paymentReference, UUID subscriptionId, UUID accountId, String newPlanId,
            int newOfferVersion, UUID periodInvoiceId, PriceQuote quote, TaxQuote tax,
            Long creditCapMinor, Instant completedAt, Instant closedAt, Closure closure) {
        this.paymentReference = Objects.requireNonNull(paymentReference, "paymentReference");
        this.subscriptionId = Objects.requireNonNull(subscriptionId, "subscriptionId");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.newPlanId = Objects.requireNonNull(newPlanId, "newPlanId");
        this.newOfferVersion = newOfferVersion;
        this.periodInvoiceId = Objects.requireNonNull(periodInvoiceId, "periodInvoiceId");
        this.quote = Objects.requireNonNull(quote, "quote");
        this.tax = Objects.requireNonNull(tax, "tax");
        this.creditCapMinor = creditCapMinor;
        this.completedAt = completedAt;
        this.closedAt = closedAt;
        this.closure = closure;
    }

    public static UpgradeIntent place(String paymentReference, UUID subscriptionId, UUID accountId, String newPlanId,
            int newOfferVersion, UUID periodInvoiceId, PriceQuote quote) {
        return place(paymentReference, subscriptionId, accountId, newPlanId, newOfferVersion,
                periodInvoiceId, quote, TaxQuote.zero());
    }

    public static UpgradeIntent place(String paymentReference, UUID subscriptionId, UUID accountId, String newPlanId,
            int newOfferVersion, UUID periodInvoiceId, PriceQuote quote, TaxQuote tax) {
        if (newOfferVersion < 1) {
            throw new IllegalArgumentException("Offer versions are numbered from 1: " + newOfferVersion);
        }
        return new UpgradeIntent(paymentReference, subscriptionId, accountId, newPlanId, newOfferVersion,
                periodInvoiceId, quote, tax, null, null, null, null);
    }

    /**
     * A copy with the credit cap set to {@code cap} (ADR-060): the credit, VAT included, is capped at
     * that amount. An intent with no cap keeps {@code null}; this is only called when a cap is needed.
     */
    public UpgradeIntent withCreditCap(Money cap) {
        Objects.requireNonNull(cap, "cap");
        return new UpgradeIntent(paymentReference, subscriptionId, accountId, newPlanId, newOfferVersion,
                periodInvoiceId, quote, tax, cap.minorUnits(), completedAt, closedAt, closure);
    }

    public UpgradeIntent completed(Instant completedAt) {
        Objects.requireNonNull(completedAt, "completedAt");
        return new UpgradeIntent(paymentReference, subscriptionId, accountId, newPlanId, newOfferVersion,
                periodInvoiceId, quote, tax, creditCapMinor, completedAt, closedAt, closure);
    }

    public UpgradeIntent closed(Closure closure, Instant closedAt) {
        Objects.requireNonNull(closure, "closure");
        Objects.requireNonNull(closedAt, "closedAt");
        return new UpgradeIntent(paymentReference, subscriptionId, accountId, newPlanId, newOfferVersion,
                periodInvoiceId, quote, tax, creditCapMinor, completedAt, closedAt, closure);
    }

    public boolean completed() {
        return completedAt != null;
    }

    /** A line with a positive amount is a charge line; one with a negative amount is a credit line. Zero counts as neither. */
    private static boolean isChargeLine(PriceLine line) {
        return line.amount().minorUnits() > 0;
    }

    private static boolean isCreditLine(PriceLine line) {
        return line.amount().minorUnits() < 0;
    }

    /** The net of the charge lines, without tax (ADR-051). */
    public static Money netChargeOf(PriceQuote quote) {
        Objects.requireNonNull(quote, "quote");
        Money net = Money.of(0, quote.total().currency().getCurrencyCode());
        for (PriceLine line : quote.lines()) {
            if (isChargeLine(line)) {
                net = net.plus(line.amount());
            }
        }
        return net;
    }

    /** The net of this intent's charge lines, without tax. */
    public Money netCharge() {
        return netChargeOf(quote);
    }

    /** The net of the credit lines as positive amounts, without tax. */
    public Money netCredit() {
        Money net = Money.of(0, quote.total().currency().getCurrencyCode());
        for (PriceLine line : quote.lines()) {
            if (isCreditLine(line)) {
                net = net.plus(Money.of(0, quote.total().currency().getCurrencyCode()).minus(line.amount()));
            }
        }
        return net;
    }

    /** VAT on the charge lines, per line (ADR-017), summed. */
    public Money chargeTax() {
        Money total = Money.of(0, quote.total().currency().getCurrencyCode());
        for (PriceLine line : quote.lines()) {
            if (isChargeLine(line)) {
                total = total.plus(line.amount().times(tax.rate()));
            }
        }
        return total;
    }

    /**
     * VAT on the credit lines, at the same rate, per line, on the positive amount. When a cap is
     * set (ADR-060), the capped VAT never exceeds what is left of the cap after the net credit:
     * {@code min(computed, creditCapMinor - netCredit())}, never below zero.
     */
    public Money creditTax() {
        Money total = Money.of(0, quote.total().currency().getCurrencyCode());
        for (PriceLine line : quote.lines()) {
            if (isCreditLine(line)) {
                total = total.plus(Money.of(0, quote.total().currency().getCurrencyCode())
                        .minus(line.amount()).times(tax.rate()));
            }
        }
        if (creditCapMinor != null) {
            long capped = Math.min(total.minorUnits(), creditCapMinor - netCredit().minorUnits());
            if (capped < 0) {
                capped = 0;
            }
            return Money.of(capped, quote.total().currency().getCurrencyCode());
        }
        return total;
    }

    /** The gross amount to collect, VAT included (ADR-051): charge lines and their VAT, minus credit lines and their VAT. */
    public Money charge() {
        return netCharge().plus(chargeTax()).minus(netCredit().plus(creditTax()));
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

    public TaxQuote tax() {
        return tax;
    }

    /** The credit cap in minor units (ADR-060), or null when there is no cap. */
    public Long creditCapMinor() {
        return creditCapMinor;
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
