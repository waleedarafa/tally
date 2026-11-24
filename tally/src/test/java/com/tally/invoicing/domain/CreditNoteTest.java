package com.tally.invoicing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.PricingReason;
import com.tally.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CreditNoteTest {

    static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    static final PricingReason REASON = new PricingReason("BASIC", 1, "fixed-price-phase", Map.of());

    static List<InvoiceLine> lines(String description, long... amounts) {
        return Arrays.stream(amounts).mapToObj(amount -> InvoiceLine.of(description, Money.of(amount, "AED")))
                .toList();
    }

    static CreditNote issue(List<InvoiceLine> lines, Money available) {
        return CreditNote.issue(UUID.randomUUID(), UUID.randomUUID(), lines, available, NOW);
    }

    @Test
    void theTotalIsTheSumOfTheLinesAndIsPositive() {
        CreditNote creditNote = issue(lines("Credit", 500, 250), Money.of(1000, "AED"));

        assertThat(creditNote.amount()).isEqualTo(Money.of(750, "AED"));
        assertThat(creditNote.currency()).isEqualTo("AED");
        assertThat(creditNote.version()).isEqualTo(CreditNote.ISSUED_VERSION);
        assertThat(creditNote.lines()).hasSize(2);
        assertThat(creditNote.lines().getFirst().amount()).isEqualTo(Money.of(500, "AED"));
        assertThat(creditNote.lines().get(1).amount()).isEqualTo(Money.of(250, "AED"));
    }

    @Test
    void aNonPositiveTotalIsRefused() {
        assertThatThrownBy(() -> issue(lines("Credit", 500, -500), Money.of(1000, "AED")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("more than zero");
        assertThatThrownBy(() -> issue(lines("Credit", -500), Money.of(1000, "AED")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("more than zero");
    }

    @Test
    void aCreditNoteCannotTotalMoreThanTheAmountPassedIn() {
        assertThatThrownBy(() -> issue(lines("Credit", 1001), Money.of(1000, "AED")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("still available");
        CreditNote creditNote = issue(lines("Credit", 1000), Money.of(1000, "AED"));
        assertThat(creditNote.amount()).isEqualTo(Money.of(1000, "AED"));
    }

    @Test
    void theCapIsTheAmountStillAvailableOnTheInvoice() {
        // An invoice of 3000 AED: the first credit note is capped at the full amount, passed in as is.
        CreditNote first = CreditNote.issue(UUID.randomUUID(), UUID.randomUUID(), lines("Credit", 2000),
                Money.of(3000, "AED"), NOW);
        assertThat(first.amount()).isEqualTo(Money.of(2000, "AED"));

        // The next credit note is capped at 3000 - 2000 = 1000, the amount still available.
        assertThatThrownBy(() -> CreditNote.issue(first.invoiceId(), first.accountId(), lines("Credit", 1001),
                Money.of(1000, "AED"), NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("still available");
        CreditNote second = CreditNote.issue(first.invoiceId(), first.accountId(), lines("Credit", 1000),
                Money.of(1000, "AED"), NOW);
        assertThat(second.amount()).isEqualTo(Money.of(1000, "AED"));
    }

    @Test
    void linesMustBeInTheInvoiceCurrency() {
        assertThatThrownBy(() -> CreditNote.issue(UUID.randomUUID(), UUID.randomUUID(),
                List.of(InvoiceLine.of("Credit", Money.of(500, "USD"))), Money.of(1000, "AED"), NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("AED");
    }

    @Test
    void theCreditNoteRefersToTheInvoiceByIdAndBelongsToItsAccount() {
        UUID invoice = UUID.randomUUID();
        UUID account = UUID.randomUUID();

        CreditNote creditNote = CreditNote.issue(invoice, account, lines("Credit", 1), Money.of(1, "AED"), NOW);

        assertThat(creditNote.invoiceId()).isEqualTo(invoice);
        assertThat(creditNote.accountId()).isEqualTo(account);
        assertThat(creditNote.id()).isNotEqualTo(invoice);
        assertThat(creditNote.issuedAt()).isEqualTo(NOW);
    }

    @Test
    void eachIssuedCreditNoteHasItsOwnId() {
        assertThat(issue(lines("Credit", 1), Money.of(1, "AED")).id())
                .isNotEqualTo(issue(lines("Credit", 1), Money.of(1, "AED")).id());
    }

    /** I1: a credit note is a document of its own; issuing one never edits the invoice it corrects. */
    @Test
    void issuingACreditNoteDoesNotModifyTheInvoice() {
        UUID account = UUID.randomUUID();
        UUID subscription = UUID.randomUUID();
        Invoice invoice = Invoice.issue(account, subscription, "BASIC", 1,
                List.of(InvoiceLine.of("Price", Money.of(3699, "AED"))), REASON,
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-02-15"), NOW, NOW);
        UUID invoiceId = invoice.id();
        Money invoiceAmount = invoice.amount();
        List<InvoiceLine> invoiceLines = invoice.lines();
        InvoiceStatus invoiceStatus = invoice.status();
        long invoiceVersion = invoice.version();

        CreditNote creditNote = CreditNote.issue(invoiceId, invoice.accountId(),
                lines("Credit", 3699), invoice.amount(), NOW);

        assertThat(creditNote.invoiceId()).isEqualTo(invoiceId);
        assertThat(creditNote.amount()).isEqualTo(invoiceAmount);
        assertThat(invoice.id()).isEqualTo(invoiceId);
        assertThat(invoice.amount()).isEqualTo(invoiceAmount);
        assertThat(invoice.lines()).isEqualTo(invoiceLines);
        assertThat(invoice.status()).isEqualTo(invoiceStatus);
        assertThat(invoice.version()).isEqualTo(invoiceVersion);
    }
}
