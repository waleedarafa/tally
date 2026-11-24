package com.tally.invoicing.adapter.persistence;

import com.tally.invoicing.domain.CreditNote;
import com.tally.invoicing.domain.CreditNotes;
import com.tally.invoicing.domain.InvoiceLine;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Spring Data implements {@link CreditNotes}; its method names stay here (CLAUDE.md rule 14). */
interface JpaCreditNotes extends JpaRepository<CreditNote, UUID>, CreditNotes {

    @Override
    default void add(CreditNote creditNote) {
        insertRow(creditNote.id(), creditNote.invoiceId(), creditNote.appliedToInvoiceId(), creditNote.accountId(),
                creditNote.amount().minorUnits(), creditNote.currency(), creditNote.issuedAt(), creditNote.version());
        // Same transaction as the credit note row: both commit, or neither does.
        List<InvoiceLine> lines = creditNote.lines();
        for (int i = 0; i < lines.size(); i++) {
            InvoiceLine line = lines.get(i);
            insertLine(creditNote.id(), i, line.description(), line.amountMinor(), line.currency());
        }
    }

    @Override
    default Optional<CreditNote> find(UUID creditNoteId) {
        return findById(creditNoteId);
    }

    @Override
    default List<CreditNote> ofInvoice(UUID invoiceId) {
        return findByInvoiceIdOrderByIssuedAtAsc(invoiceId);
    }

    @Override
    default List<CreditNote> appliedTo(UUID invoiceId) {
        return findByAppliedToInvoiceIdOrderByIssuedAtAsc(invoiceId);
    }

    List<CreditNote> findByInvoiceIdOrderByIssuedAtAsc(UUID invoiceId);

    List<CreditNote> findByAppliedToInvoiceIdOrderByIssuedAtAsc(UUID invoiceId);

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO invoicing.credit_note (id, invoice_id, applied_to_invoice_id, account_id, amount_minor,
                currency, issued_at, version)
            VALUES (:id, :invoiceId, :appliedToInvoiceId, :accountId, :amountMinor, :currency, :issuedAt, :version)
            """)
    int insertRow(UUID id, UUID invoiceId, UUID appliedToInvoiceId, UUID accountId, long amountMinor, String currency,
            Instant issuedAt, long version);

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO invoicing.credit_note_line (credit_note_id, line_number, description, amount_minor, currency)
            VALUES (:creditNoteId, :lineNumber, :description, :amountMinor, :currency)
            """)
    int insertLine(UUID creditNoteId, int lineNumber, String description, long amountMinor, String currency);
}
