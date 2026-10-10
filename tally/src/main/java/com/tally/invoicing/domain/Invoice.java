package com.tally.invoicing.domain;

import com.tally.invoicing.InvoiceStatus;
import com.tally.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
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

    @Column(name = "payment_reference", nullable = false)
    private String paymentReference;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    /** When the invoice was due to be billed: local midnight of its billing date, or the subscribe time (D6). */
    @Column(name = "billed_for")
    private Instant billedFor;

    @Column(name = "paid_at")
    private Instant paidAt;

    /**
     * Optimistic lock. A listener may run twice at the same time (Spike A): without it, two concurrent
     * deliveries of one PaymentSucceeded could both mark the invoice paid and both publish InvoicePaid.
     */
    @Version
    private long version;

    protected Invoice() {
    }

    public static Invoice issue(UUID accountId, UUID subscriptionId, String planId, Money amount,
            LocalDate periodStart, LocalDate periodEnd, Instant billedFor, Instant now) {
        Invoice i = new Invoice();
        i.id = UUID.randomUUID();
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
        return i;
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
