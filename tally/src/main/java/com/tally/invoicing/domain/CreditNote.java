package com.tally.invoicing.domain;

import com.tally.shared.Money;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A credit note against one invoice. The invoice itself never changes (I1, ADR-040): a correction is a
 * document of its own, referencing the invoice by id and belonging to the invoice's account. An issued
 * credit note never changes either.
 *
 * <p>The credit note's total is the sum of its lines, like the invoice's, and it is positive: owing the
 * customer money is this document's job, never an invoice's (I1). Lines are the credit's lines,
 * exactly as the invoice carries the quote's lines; a credit note is issued with lines when the invoice
 * it corrects carries them.
 *
 * <p>The cap is the amount still available on the invoice. For one invoice, the total credited across
 * every credit note against it never exceeds the invoice's amount, so the caller passes the amount still
 * available, the invoice's total minus the total of the credit notes already issued against it, and the
 * credit note refuses to total more than it. For the first credit note against an invoice this is the
 * invoice's total. Several credit notes may exist against one invoice (ADR-040), and each is capped
 * against what is still available at its issue time.
 */
@Entity
@Table(schema = "invoicing", name = "credit_note")
public class CreditNote {

    @Id
    private UUID id;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    /**
     * The invoice this credit is applied to when settling a later charge, or null when the credit is
     * only a correction of {@link #invoiceId}. For an upgrade, the note corrects the paid period
     * invoice (ADR-042) and is applied to the adjustment (the new-plan charge).
     */
    @Column(name = "applied_to_invoice_id")
    private UUID appliedToInvoiceId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false)
    private String currency;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(schema = "invoicing", name = "credit_note_line", joinColumns = @JoinColumn(name = "credit_note_id"))
    @OrderColumn(name = "line_number")
    private List<InvoiceLine> lines = new ArrayList<>();

    /** The version a credit note is issued at: the sequence CreditNoteIssued carries (ADR-016). */
    public static final long ISSUED_VERSION = 1;

    /** Optimistic lock; see {@code Invoice}'s comment for the concurrency reason. */
    @Version
    private long version;

    protected CreditNote() {
    }

    /**
     * Issues one credit note against {@code invoiceId}, belonging to {@code accountId} (the invoice's
     * account), carrying {@code lines} with the sum of their amounts as the total. The credit note
     * refers to the invoice by its id only and never edits it (I1).
     *
     * @param available the amount still available on the invoice for credit, in the invoice's currency:
     *            the invoice's total minus the total of the credit notes already issued against it
     *            (ADR-040). The total must be greater than zero and not exceed this.
     * @param issuedAt the time the credit note is issued, from the injected clock
     */
    /** The credit note for the first adjustment of a period invoice (an upgrade). The same inputs always spell the same id (D1). */
    public static UUID idOfUpgradeCredit(UUID periodInvoiceId, int adjustmentSequence) {
        Objects.requireNonNull(periodInvoiceId, "periodInvoiceId");
        if (adjustmentSequence < 1) {
            throw new IllegalArgumentException("An adjustment's sequence starts at 1: " + adjustmentSequence);
        }
        return UUID.nameUUIDFromBytes(("credit-note:upgrade:" + periodInvoiceId + ":" + adjustmentSequence)
                .getBytes(StandardCharsets.UTF_8));
    }

    public static CreditNote issue(UUID invoiceId, UUID accountId, List<InvoiceLine> lines, Money available,
            Instant issuedAt) {
        return issueWithId(UUID.randomUUID(), invoiceId, accountId, lines, available, issuedAt, null);
    }

    /**
     * Issues a credit note with a caller-chosen id, so a retry of the same upgrade writes the same document (D1).
     * {@link #issue} keeps choosing a random id for notes that have no natural key.
     */
    public static CreditNote issueWithId(UUID id, UUID invoiceId, UUID accountId, List<InvoiceLine> lines,
            Money available, Instant issuedAt) {
        return issueWithId(id, invoiceId, accountId, lines, available, issuedAt, null);
    }

    /**
     * {@code appliedToInvoiceId} is the invoice this credit settles, when that is not the invoice it
     * corrects. It is recorded at issue and never changes.
     */
    public static CreditNote issueWithId(UUID id, UUID invoiceId, UUID accountId, List<InvoiceLine> lines,
            Money available, Instant issuedAt, UUID appliedToInvoiceId) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(invoiceId, "invoiceId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(issuedAt, "issuedAt");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("A credit note has at least one line");
        }
        for (InvoiceLine line : lines) {
            if (!line.amount().currency().equals(available.currency())) {
                throw new IllegalArgumentException("Line " + line.description() + " is " + line.amount()
                        + ", not the invoice's " + available.currency());
            }
        }
        Money total = lines.stream().map(InvoiceLine::amount).reduce(Money::plus).orElseThrow();
        if (total.minorUnits() <= 0) {
            throw new IllegalArgumentException("A credit note must credit more than zero: " + total
                    + " against invoice " + invoiceId);
        }
        if (total.minorUnits() > available.minorUnits()) {
            throw new IllegalArgumentException("A credit note cannot credit more than the " + available
                    + " still available on invoice " + invoiceId + ": " + total);
        }
        CreditNote creditNote = new CreditNote();
        creditNote.id = id;
        creditNote.invoiceId = invoiceId;
        creditNote.appliedToInvoiceId = appliedToInvoiceId;
        creditNote.accountId = accountId;
        creditNote.lines = new ArrayList<>(lines);
        creditNote.amountMinor = total.minorUnits();
        creditNote.currency = total.currency().getCurrencyCode();
        creditNote.issuedAt = issuedAt;
        creditNote.version = ISSUED_VERSION;
        return creditNote;
    }

    public UUID id() {
        return id;
    }

    public UUID invoiceId() {
        return invoiceId;
    }

    public UUID appliedToInvoiceId() {
        return appliedToInvoiceId;
    }

    public UUID accountId() {
        return accountId;
    }

    public Money amount() {
        return Money.of(amountMinor, currency);
    }

    public String currency() {
        return currency;
    }

    public List<InvoiceLine> lines() {
        return List.copyOf(lines);
    }

    public long version() {
        return version;
    }

    public Instant issuedAt() {
        return issuedAt;
    }
}
