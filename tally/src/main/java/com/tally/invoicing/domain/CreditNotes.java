package com.tally.invoicing.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Invoicing's credit notes (a port; {@code adapter.persistence} implements it, F9). */
public interface CreditNotes {

    /**
     * Saves {@code creditNote}. A credit note is immutable once issued: there is no update.
     */
    void add(CreditNote creditNote);

    Optional<CreditNote> find(UUID creditNoteId);

    /** The invoice's credit notes, oldest first. */
    List<CreditNote> ofInvoice(UUID invoiceId);

    /** Credit notes applied to this invoice when settling it, oldest first. */
    List<CreditNote> appliedTo(UUID invoiceId);
}

