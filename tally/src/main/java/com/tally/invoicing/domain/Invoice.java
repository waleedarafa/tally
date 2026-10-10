package com.tally.invoicing.domain;

import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.PricingReason;
import com.tally.shared.Money;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** An invoice for one billing period. Refers to the account and subscription by identifier only. */
@Entity
@Table(schema = "invoicing", name = "invoice")
public class Invoice {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "plan_id", nullable = false)
    private String planId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceStatus status;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false)
    private String currency;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    /** The subscription's period this invoice bills (I2). Null only for an invoice issued before Slice 1R PR-5. */
    @Column(name = "period_number")
    private Integer periodNumber;

    @Column(name = "payment_reference", nullable = false)
    private String paymentReference;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    /** When the invoice was due to be billed: local midnight of its billing date, or the subscribe time (D6). */
    @Column(name = "billed_for")
    private Instant billedFor;

    @Column(name = "paid_at")
    private Instant paidAt;

    /** The PriceQuote's lines (I3); the amount is their sum. Empty for an invoice issued before Slice 1R PR-4. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(schema = "invoicing", name = "invoice_line", joinColumns = @JoinColumn(name = "invoice_id"))
    @OrderColumn(name = "line_number")
    private List<InvoiceLine> lines = new ArrayList<>();

    /** The offer version the quote priced, and the quote's reason (D7). Null before Slice 1R PR-4. */
    @Column(name = "offer_version")
    private Integer offerVersion;

    /** Written once, by the insert that issues the invoice (jsonb); never updated. */
    @Column(name = "quote_reason", columnDefinition = "jsonb", insertable = false, updatable = false)
    private PricingReason quoteReason;

    /**
     * Optimistic lock. A listener may run twice at the same time (Spike A): without it, two concurrent
     * deliveries of one PaymentSucceeded could both mark the invoice paid and both publish InvoicePaid.
     */
    @Version
    private long version;

    protected Invoice() {
    }

    /**
     * Issues the invoice for one period of a subscription, from a quote's amounts (design.md §7: takes
     * amounts, never an offer). The amount is the sum of the lines, never rounded again (ADR-017). I2: the
     * ID is derived from the subscription and period number, so there is one invoice per period. An
     * invoice whose total is zero is paid when issued: there is nothing to collect. A negative total is
     * refused; the daily invariant checks the same rule a second time.
     */
    public static Invoice issue(UUID accountId, UUID subscriptionId, String planId, int periodNumber,
            List<InvoiceLine> lines, PricingReason quoteReason, LocalDate periodStart, LocalDate periodEnd,
            Instant billedFor, Instant now) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("An invoice has at least one line");
        }
        Money amount = lines.stream().map(InvoiceLine::amount).reduce(Money::plus).orElseThrow();
        if (amount.minorUnits() < 0) {
            // Owing the customer money is a credit note's job (I1), never an invoice's (FR-024).
            throw new IllegalArgumentException("An invoice cannot total less than zero: " + amount
                    + " for period " + periodNumber + " of subscription " + subscriptionId);
        }
        Invoice i = new Invoice();
        i.lines = new ArrayList<>(lines);
        i.offerVersion = quoteReason.offerVersion();
        i.quoteReason = quoteReason;
        i.id = idOf(subscriptionId, periodNumber);
        i.periodNumber = periodNumber;
        i.accountId = accountId;
        i.subscriptionId = subscriptionId;
        i.planId = planId;
        i.status = InvoiceStatus.PAYMENT_PENDING;
        i.amountMinor = amount.minorUnits();
        i.currency = amount.currency().getCurrencyCode();
        i.periodStart = periodStart;
        i.periodEnd = periodEnd;
        // The opaque reference Payment reports results by (FR-014).
        i.paymentReference = "INV-" + i.id;
        i.issuedAt = now;
        i.billedFor = billedFor;
        if (amount.minorUnits() == 0) {
            i.status = InvoiceStatus.PAID;
            i.paidAt = now;
        }
        return i;
    }

    /** I2: invoice ID = subscription + period number, as a name-based UUID. */
    public static UUID idOf(UUID subscriptionId, int periodNumber) {
        return UUID.nameUUIDFromBytes(("invoice:" + subscriptionId + ":" + periodNumber)
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns whether the invoice changed. A failed invoice can still be paid: reconciliation may
     * find that a charge reported as failed went through (FR-018).
     */
    public boolean paid(Instant now) {
        if (status == InvoiceStatus.PAID) {
            return false;
        }
        status = InvoiceStatus.PAID;
        paidAt = now;
        return true;
    }

    /** Recorded only: no retries, no blocking in slice 1. */
    public boolean paymentFailed() {
        if (status != InvoiceStatus.PAYMENT_PENDING) {
            return false;
        }
        status = InvoiceStatus.PAYMENT_FAILED;
        return true;
    }

    public UUID id() {
        return id;
    }

    public UUID accountId() {
        return accountId;
    }

    public UUID subscriptionId() {
        return subscriptionId;
    }

    public String planId() {
        return planId;
    }

    public InvoiceStatus status() {
        return status;
    }

    public List<InvoiceLine> lines() {
        return List.copyOf(lines);
    }

    public Integer offerVersion() {
        return offerVersion;
    }

    public Integer periodNumber() {
        return periodNumber;
    }

    public Instant paidAt() {
        return paidAt;
    }

    public PricingReason quoteReason() {
        return quoteReason;
    }

    public Money amount() {
        return Money.of(amountMinor, currency);
    }

    public LocalDate periodStart() {
        return periodStart;
    }

    public LocalDate periodEnd() {
        return periodEnd;
    }

    public String paymentReference() {
        return paymentReference;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public Instant billedFor() {
        return billedFor;
    }
}
