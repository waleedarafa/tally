package com.tally.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.AbstractIntegrationTest;
import com.tally.invoicing.domain.CreditNote;
import com.tally.invoicing.domain.CreditNotes;
import com.tally.invoicing.domain.InvoiceLine;
import com.tally.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** Saves and loads a credit note: lines, amount and ids survive the round trip. */
class CreditNotePersistenceIT extends AbstractIntegrationTest {

    static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");

    @Autowired
    CreditNotes creditNotes;

    @Autowired
    TransactionTemplate transactions;

    @Test
    void aCreditNoteIsSavedAndLoaded() {
        UUID account = UUID.randomUUID();
        UUID invoice = UUID.randomUUID();
        List<InvoiceLine> lines = List.of(
                InvoiceLine.of("Credit, 14 of 30 days", Money.of(1500, "AED")),
                InvoiceLine.of("Credit, VAT", Money.of(150, "AED")));
        CreditNote creditNote = CreditNote.issue(invoice, account, lines, Money.of(3000, "AED"), NOW);

        transactions.executeWithoutResult(tx -> creditNotes.add(creditNote));

        CreditNote loaded = creditNotes.find(creditNote.id()).orElseThrow();
        assertThat(loaded.id()).isEqualTo(creditNote.id());
        assertThat(loaded.invoiceId()).isEqualTo(invoice);
        assertThat(loaded.accountId()).isEqualTo(account);
        assertThat(loaded.amount()).isEqualTo(Money.of(1650, "AED"));
        assertThat(loaded.currency()).isEqualTo("AED");
        assertThat(loaded.lines()).hasSize(2);
        assertThat(loaded.lines().getFirst().description()).isEqualTo("Credit, 14 of 30 days");
        assertThat(loaded.lines().getFirst().amount()).isEqualTo(Money.of(1500, "AED"));
        assertThat(loaded.issuedAt()).isEqualTo(NOW);
        assertThat(loaded.version()).isEqualTo(CreditNote.ISSUED_VERSION);

        assertThat(creditNotes.ofInvoice(invoice)).extracting(CreditNote::id).containsExactly(creditNote.id());
    }

    @Test
    void severalCreditNotesAgainstOneInvoiceAreAllLoaded() {
        UUID account = UUID.randomUUID();
        UUID invoice = UUID.randomUUID();
        CreditNote first = CreditNote.issue(invoice, account,
                List.of(InvoiceLine.of("Credit", Money.of(1000, "AED"))), Money.of(3000, "AED"), NOW);
        CreditNote second = CreditNote.issue(invoice, account,
                List.of(InvoiceLine.of("Credit", Money.of(500, "AED"))), Money.of(2000, "AED"), NOW.plusSeconds(60));

        transactions.executeWithoutResult(tx -> {
            creditNotes.add(first);
            creditNotes.add(second);
        });

        assertThat(creditNotes.ofInvoice(invoice)).extracting(CreditNote::id)
                .containsExactly(first.id(), second.id());
    }
}
