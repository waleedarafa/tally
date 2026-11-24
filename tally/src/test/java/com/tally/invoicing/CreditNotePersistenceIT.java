package com.tally.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tally.AbstractIntegrationTest;
import com.tally.invoicing.domain.CreditNote;
import com.tally.invoicing.domain.CreditNotes;
import com.tally.invoicing.domain.Invoice;
import com.tally.invoicing.domain.InvoiceLine;
import com.tally.invoicing.domain.Invoices;
import com.tally.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

/** Saves and loads a credit note: lines, amount and ids survive the round trip. Every credit note is saved against an invoice that was persisted first. */
class CreditNotePersistenceIT extends AbstractIntegrationTest {

    static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");

    @Autowired
    CreditNotes creditNotes;

    @Autowired
    Invoices invoices;

    @Autowired
    TransactionTemplate transactions;

    /** Persists one real invoice, priced at {@code amountMinor}, and returns it. */
    private Invoice persistInvoice(long amountMinor) {
        Invoice invoice = Invoice.issue(UUID.randomUUID(), UUID.randomUUID(), "BASIC", 1,
                List.of(InvoiceLine.of("Price", Money.of(amountMinor, "AED"))),
                new PricingReason("BASIC", 1, "fixed-price-phase", Map.of()),
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-02-15"), NOW, NOW);
        transactions.executeWithoutResult(tx -> assertThat(invoices.addIfAbsent(invoice)).isTrue());
        return invoice;
    }

    @Test
    void aCreditNoteIsSavedAndLoaded() {
        Invoice invoice = persistInvoice(3000);
        UUID account = invoice.accountId();
        List<InvoiceLine> lines = List.of(
                InvoiceLine.of("Credit, 14 of 30 days", Money.of(1500, "AED")),
                InvoiceLine.of("Credit, VAT", Money.of(150, "AED")));
        CreditNote creditNote = CreditNote.issue(invoice.id(), account, lines, Money.of(3000, "AED"), NOW);

        transactions.executeWithoutResult(tx -> creditNotes.add(creditNote));

        CreditNote loaded = creditNotes.find(creditNote.id()).orElseThrow();
        assertThat(loaded.id()).isEqualTo(creditNote.id());
        assertThat(loaded.invoiceId()).isEqualTo(invoice.id());
        assertThat(loaded.accountId()).isEqualTo(account);
        assertThat(loaded.amount()).isEqualTo(Money.of(1650, "AED"));
        assertThat(loaded.currency()).isEqualTo("AED");
        assertThat(loaded.lines()).hasSize(2);
        assertThat(loaded.lines().getFirst().description()).isEqualTo("Credit, 14 of 30 days");
        assertThat(loaded.lines().getFirst().amount()).isEqualTo(Money.of(1500, "AED"));
        assertThat(loaded.issuedAt()).isEqualTo(NOW);
        assertThat(loaded.version()).isEqualTo(CreditNote.ISSUED_VERSION);

        assertThat(creditNotes.ofInvoice(invoice.id())).extracting(CreditNote::id).containsExactly(creditNote.id());
    }

    @Test
    void severalCreditNotesAgainstOneInvoiceAreAllLoaded() {
        Invoice invoice = persistInvoice(3000);
        UUID account = invoice.accountId();
        CreditNote first = CreditNote.issue(invoice.id(), account,
                List.of(InvoiceLine.of("Credit", Money.of(1000, "AED"))), Money.of(3000, "AED"), NOW);
        CreditNote second = CreditNote.issue(invoice.id(), account,
                List.of(InvoiceLine.of("Credit", Money.of(500, "AED"))), Money.of(2000, "AED"), NOW.plusSeconds(60));

        transactions.executeWithoutResult(tx -> {
            creditNotes.add(first);
            creditNotes.add(second);
        });

        assertThat(creditNotes.ofInvoice(invoice.id())).extracting(CreditNote::id)
                .containsExactly(first.id(), second.id());
    }

    @Test
    void aCreditNoteForAnUnknownInvoiceIsRefused() {
        CreditNote creditNote = CreditNote.issue(UUID.randomUUID(), UUID.randomUUID(),
                List.of(InvoiceLine.of("Credit", Money.of(100, "AED"))), Money.of(1000, "AED"), NOW);

        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> creditNotes.add(creditNote)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void aCreditNoteThatTakesTheTotalAboveTheInvoiceAmountIsRefused() {
        Invoice invoice = persistInvoice(1000);
        UUID account = invoice.accountId();
        CreditNote first = CreditNote.issue(invoice.id(), account,
                List.of(InvoiceLine.of("Credit, first", Money.of(600, "AED"))), Money.of(1000, "AED"), NOW);
        transactions.executeWithoutResult(tx -> creditNotes.add(first));

        // The factory is told the full amount is still available; the database must refuse this anyway.
        CreditNote second = CreditNote.issue(invoice.id(), account,
                List.of(InvoiceLine.of("Credit, second", Money.of(500, "AED"))), Money.of(1000, "AED"), NOW.plusSeconds(60));
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> creditNotes.add(second)))
                .isInstanceOf(DataAccessException.class);

        assertThat(creditNotes.ofInvoice(invoice.id())).extracting(CreditNote::id)
                .containsExactly(first.id());
    }
}
