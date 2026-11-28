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
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

    /** What kind of document the invoice is (ADR-040): the period invoice, or an adjustment for it. */
    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_type", nullable = false)
    private InvoiceKind invoiceType;

    /** A period invoice is never tied to one; an adjustment is tied to the period invoice it bills for (ADR-040). */
    @Column(name = "period_invoice_id")
    private UUID periodInvoiceId;

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

    /** Null while the invoice is unissued (DRAFT or TAX_QUOTED); set at issue. */
    @Column(name = "issued_at")
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

    /** The version an invoice is issued at: the sequence InvoiceIssued carries (ADR-016). */
    public static final long ISSUED_VERSION = 1;

    /**
     * Optimistic lock. A listener may run twice at the same time (Spike A): without it, two concurrent
     * deliveries of one PaymentSucceeded could both mark the invoice paid and both publish InvoicePaid.
     * Also the sequence its events carry (ADR-016).
     */
    @Version
    private long version;

    /** Whether this invoice has been loaded from or written to the database; only then does issue not set the version. */
    @Transient
    private boolean persisted;

    @PostLoad
    void markPersisted() {
        this.persisted = true;
    }

    protected Invoice() {
    }

    /**
     * Drafts the period invoice for one period of a subscription, from a quote's amounts (design.md §7:
     * takes amounts, never an offer). The amount is the sum of the lines, never rounded again
     * (ADR-017). I2: the ID is derived from the subscription and period number, so there is one invoice
     * per period. A negative total is refused; the daily invariant checks the same rule a second time.
     * The invoice is unissued: status DRAFT, no issuedAt, version 0.
     */
    public static Invoice draft(UUID accountId, UUID subscriptionId, String planId, int periodNumber,
            List<InvoiceLine> lines, PricingReason quoteReason, LocalDate periodStart, LocalDate periodEnd,
            Instant billedFor) {
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
        i.status = InvoiceStatus.DRAFT;
        i.issuedAt = null;
        i.version = 0L;
        i.persisted = false;
        i.lines = new ArrayList<>(lines);
        i.offerVersion = quoteReason.offerVersion();
        i.quoteReason = quoteReason;
        i.id = idOf(subscriptionId, periodNumber);
        i.invoiceType = InvoiceKind.PERIOD;
        i.periodNumber = periodNumber;
        i.accountId = accountId;
        i.subscriptionId = subscriptionId;
        i.planId = planId;
        i.amountMinor = amount.minorUnits();
        i.currency = amount.currency().getCurrencyCode();
        i.periodStart = periodStart;
        i.periodEnd = periodEnd;
        // The opaque reference Payment reports results by (FR-014).
        i.paymentReference = "INV-" + i.id;
        i.billedFor = billedFor;
        return i;
    }

    /**
     * Quotes tax on a draft (ADR-050): per-line half-up (ADR-017), summed, with the tax line appended
     * when the total is positive. Zero tax adds no line.
     */
    public void quoteTax(TaxQuote tax) {
        if (status != InvoiceStatus.DRAFT) {
            throw new IllegalStateException("Tax can only be quoted on a DRAFT invoice: " + status);
        }
        Objects.requireNonNull(tax, "tax");
        Money taxTotal = Money.of(0, currency);
        for (InvoiceLine line : lines) {
            taxTotal = taxTotal.plus(line.amount().times(tax.rate()));
        }
        if (taxTotal.minorUnits() > 0) {
            lines.add(InvoiceLine.of(tax.description(), taxTotal));
            amountMinor = amount().plus(taxTotal).minorUnits();
        }
        status = InvoiceStatus.TAX_QUOTED;
    }

    /**
     * Issues the invoice (ADR-050): TAX_QUOTED → PAYMENT_PENDING, or PAID when the gross total is zero.
     * A stored draft takes its version from the database, so a zero total is issued in two writes: the
     * issue (PAYMENT_PENDING), then paid(now); the two events carry two versions (ADR-016).
     */
    public void issue(Instant now) {
        if (status != InvoiceStatus.TAX_QUOTED) {
            throw new IllegalStateException("Only a TAX_QUOTED invoice can be issued: " + status);
        }
        this.issuedAt = Objects.requireNonNull(now, "now");
        if (amountMinor == 0 && !persisted) {
            // Issued and paid in one step: two changes, so InvoiceIssued and InvoicePaid stay in order.
            this.status = InvoiceStatus.PAID;
            this.paidAt = now;
            this.version = ISSUED_VERSION + 1;
        } else {
            this.status = InvoiceStatus.PAYMENT_PENDING;
            if (!persisted) {
                this.version = ISSUED_VERSION;
            }
        }
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
        Invoice i = draft(accountId, subscriptionId, planId, periodNumber, lines, quoteReason, periodStart,
                periodEnd, billedFor);
        i.quoteTax(TaxQuote.zero());
        i.issue(now);
        return i;
    }

    /** I2: invoice ID = subscription + period number, as a name-based UUID. */
    public static UUID idOf(UUID subscriptionId, int periodNumber) {
        return UUID.nameUUIDFromBytes(("invoice:" + subscriptionId + ":" + periodNumber)
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * I2, as amended by ADR-040: an adjustment invoice is a separate document, tied to the period
     * invoice it bills for. Its ID is a pure function of the period invoice and the sequence for the
     * period, so a retry of the same adjustment derives the same ID and issues the same document (D1).
     * The prefix keeps it distinct from the period invoice's ID. The string is stored data and must
     * never change.
     */
    public static UUID idOfAdjustment(UUID periodInvoiceId, int sequence) {
        return UUID.nameUUIDFromBytes(("adjustment-invoice:" + periodInvoiceId + ":" + sequence)
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Issues an adjustment invoice tied to {@code periodInvoice}: the same account, subscription,
     * period number and period dates, a different ID, and the same rules as a period invoice (an
     * issued invoice never changes; a zero total is paid when issued; a negative total is refused:
     * owing the customer money is a credit note's job, I1, never an invoice's). The adjustment
     * carries its own PriceQuote reason, exactly like the period invoice it follows (D7).
     *
     * @param sequence which adjustment of the period this is, numbered from 1; a period and sequence
     *            always spell the same document, so a retry issues the same document (D1)
     * @param planId the plan the adjustment bills at (for an upgrade, the new plan)
     * @param billedFor when the invoice was due to be billed (D6), usually the change date
     * @param now the time the adjustment is issued, from the injected clock
     */
    public static Invoice issueAdjustment(Invoice periodInvoice, int sequence, String planId,
            List<InvoiceLine> lines, PricingReason quoteReason, Instant billedFor, Instant now) {
        Objects.requireNonNull(periodInvoice, "periodInvoice");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(quoteReason, "quoteReason");
        Objects.requireNonNull(lines, "lines");
        if (sequence < 1) {
            throw new IllegalArgumentException("An adjustment's sequence starts at 1: " + sequence);
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("An invoice has at least one line");
        }
        Money amount = lines.stream().map(InvoiceLine::amount).reduce(Money::plus).orElseThrow();
        if (amount.minorUnits() < 0) {
            // Owing the customer money is a credit note's job (I1), never an invoice's (FR-024).
            throw new IllegalArgumentException("An invoice cannot total less than zero: " + amount
                    + " for period " + periodInvoice.periodNumber() + " of subscription "
                    + periodInvoice.subscriptionId());
        }
        Invoice i = new Invoice();
        i.lines = new ArrayList<>(lines);
        i.offerVersion = quoteReason.offerVersion();
        i.quoteReason = quoteReason;
        i.id = idOfAdjustment(periodInvoice.id(), sequence);
        i.invoiceType = InvoiceKind.ADJUSTMENT;
        i.periodInvoiceId = periodInvoice.id();
        i.periodNumber = periodInvoice.periodNumber();
        i.accountId = periodInvoice.accountId();
        i.subscriptionId = periodInvoice.subscriptionId();
        i.planId = planId;
        i.status = InvoiceStatus.PAYMENT_PENDING;
        i.amountMinor = amount.minorUnits();
        i.currency = amount.currency().getCurrencyCode();
        i.periodStart = periodInvoice.periodStart();
        i.periodEnd = periodInvoice.periodEnd();
        // The opaque reference Payment reports results by (FR-014).
        i.paymentReference = "INV-" + i.id;
        i.issuedAt = now;
        i.billedFor = billedFor;
        i.version = ISSUED_VERSION;
        if (amount.minorUnits() == 0) {
            // Issued and paid in one insert: two changes, so InvoiceIssued and InvoicePaid stay in order.
            i.status = InvoiceStatus.PAID;
            i.paidAt = now;
            i.version = ISSUED_VERSION + 1;
        }
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

    public List<InvoiceLine> lines() {
        return List.copyOf(lines);
    }

    public Integer offerVersion() {
        return offerVersion;
    }

    public Integer periodNumber() {
        return periodNumber;
    }

    /** The kind of document this invoice is (ADR-040): the period invoice, or an adjustment for it. */
    public InvoiceKind invoiceType() {
        return invoiceType;
    }

    /** The period invoice this adjustment bills for; null on a period invoice. */
    public UUID periodInvoiceId() {
        return periodInvoiceId;
    }

    /** After {@link Invoices#update}, the version the last change committed. */
    public long version() {
        return version;
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
