package com.tally.invoicing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.PricingReason;
import com.tally.shared.Money;
import com.tally.shared.Ratio;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvoiceTest {

    static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    static final PricingReason REASON = new PricingReason("BASIC", 1, "fixed-price-phase", Map.of());

    static Invoice issue(UUID subscription, int period, List<InvoiceLine> lines) {
        return Invoice.issue(UUID.randomUUID(), subscription, "BASIC", period, lines, REASON,
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-02-15"), NOW, NOW);
    }

    static Invoice draft(UUID subscription, int period, List<InvoiceLine> lines) {
        return Invoice.draft(UUID.randomUUID(), subscription, "BASIC", period, lines, REASON,
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-02-15"), NOW);
    }

    @Test
    void theAmountIsTheSumOfTheLines() {
        Invoice invoice = issue(UUID.randomUUID(), 1, List.of(InvoiceLine.of("Price", Money.of(3699, "AED")),
                InvoiceLine.of("Discount", Money.of(-699, "AED"))));

        assertThat(invoice.amount()).isEqualTo(Money.of(3000, "AED"));
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.PAYMENT_PENDING);
    }

    /** A credit that exceeds the price would make the customer owed money: that is a credit note, never an invoice. */
    @Test
    void aNegativeTotalIsRefused() {
        assertThatThrownBy(() -> issue(UUID.randomUUID(), 1, List.of(InvoiceLine.of("Price", Money.of(999, "USD")),
                InvoiceLine.of("Credit", Money.of(-1500, "USD")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("less than zero");
    }

    @Test
    void aZeroTotalIsPaidWhenIssued() {
        Invoice invoice = issue(UUID.randomUUID(), 1, List.of(InvoiceLine.of("Price", Money.of(999, "USD")),
                InvoiceLine.of("Free trial", Money.of(-999, "USD"))));

        assertThat(invoice.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(invoice.paidAt()).isEqualTo(NOW);
    }

    /** I2: one invoice ID per subscription and period. */
    @Test
    void theIdIsTheSubscriptionAndPeriod() {
        UUID subscription = UUID.randomUUID();
        List<InvoiceLine> lines = List.of(InvoiceLine.of("Price", Money.of(999, "USD")));

        assertThat(issue(subscription, 2, lines).id()).isEqualTo(issue(subscription, 2, lines).id())
                .isEqualTo(Invoice.idOf(subscription, 2))
                .isNotEqualTo(Invoice.idOf(subscription, 3));
    }

    /**
     * ADR-016: the version is the sequence the invoice's events carry. Issued at 1; a zero invoice is
     * issued and paid in one insert, at 2, so InvoiceIssued (1) still comes before InvoicePaid (2).
     */
    @Test
    void anInvoiceIsIssuedAtVersionOneAndAZeroInvoiceIsAlsoPaidAtTwo() {
        Invoice due = issue(UUID.randomUUID(), 1, List.of(InvoiceLine.of("Price", Money.of(999, "USD"))));
        Invoice zero = issue(UUID.randomUUID(), 1, List.of(InvoiceLine.of("Price", Money.of(999, "USD")),
                InvoiceLine.of("Discount", Money.of(-999, "USD"))));

        assertThat(due.version()).isEqualTo(Invoice.ISSUED_VERSION).isEqualTo(1);
        assertThat(zero.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(zero.version()).isEqualTo(2);
    }

    /** draft → quoteTax → issue (ADR-050): the unissued invoice carries no issuedAt, then moves through the states. */
    @Test
    void draftQuoteTaxAndIssueMovesThroughTheStates() {
        Invoice invoice = draft(UUID.randomUUID(), 1, List.of(InvoiceLine.of("Price", Money.of(999, "AED"))));

        assertThat(invoice.status()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(invoice.issuedAt()).isNull();
        assertThat(invoice.version()).isZero();

        invoice.quoteTax(new TaxQuote(Ratio.of(5, 100), "VAT (5%)"));

        assertThat(invoice.status()).isEqualTo(InvoiceStatus.TAX_QUOTED);
        assertThat(invoice.lines()).hasSize(2);
        assertThat(invoice.lines().get(1).description()).isEqualTo("VAT (5%)");
        // 999 fils at 5% is 49.95, half-up to 50 (ADR-017).
        assertThat(invoice.lines().get(1).amount()).isEqualTo(Money.of(50, "AED"));
        assertThat(invoice.amount()).isEqualTo(Money.of(1049, "AED"));

        invoice.issue(NOW);

        assertThat(invoice.status()).isEqualTo(InvoiceStatus.PAYMENT_PENDING);
        assertThat(invoice.issuedAt()).isEqualTo(NOW);
        assertThat(invoice.version()).isEqualTo(Invoice.ISSUED_VERSION);
    }

    /** Zero tax adds no VAT line (ADR-050). */
    @Test
    void zeroTaxQuoteAddsNoVatLine() {
        Invoice invoice = draft(UUID.randomUUID(), 1, List.of(InvoiceLine.of("Price", Money.of(999, "USD"))));

        invoice.quoteTax(TaxQuote.zero());

        assertThat(invoice.status()).isEqualTo(InvoiceStatus.TAX_QUOTED);
        assertThat(invoice.lines()).hasSize(1);
        assertThat(invoice.amount()).isEqualTo(Money.of(999, "USD"));
    }

    /** ADR-017: per-line half-up, summed; the tax line is the sum of the rounded lines. */
    @Test
    void perLineHalfUpRoundingMatchesADR017Goldens() {
        Invoice single = draft(UUID.randomUUID(), 1, List.of(InvoiceLine.of("Price", Money.of(1417, "AED"))));
        single.quoteTax(new TaxQuote(Ratio.of(5, 100), "VAT (5%)"));
        assertThat(single.lines().get(1).amount()).isEqualTo(Money.of(71, "AED"));
        assertThat(single.amount()).isEqualTo(Money.of(1488, "AED"));

        Invoice multi = draft(UUID.randomUUID(), 2, List.of(InvoiceLine.of("Price", Money.of(1417, "AED")),
                InvoiceLine.of("More price", Money.of(100, "AED"))));
        multi.quoteTax(new TaxQuote(Ratio.of(5, 100), "VAT (5%)"));
        assertThat(multi.lines().get(2).amount()).isEqualTo(Money.of(76, "AED"));

        Invoice saudi = draft(UUID.randomUUID(), 3, List.of(InvoiceLine.of("Price", Money.of(3000, "SAR"))));
        saudi.quoteTax(new TaxQuote(Ratio.of(15, 100), "VAT (15%)"));
        assertThat(saudi.lines().get(1).amount()).isEqualTo(Money.of(450, "SAR"));
        assertThat(saudi.amount()).isEqualTo(Money.of(3450, "SAR"));
    }

    /** A total of zero after tax is paid when issued: there is nothing to collect. */
    @Test
    void zeroTotalAfterTaxIsPaidWhenIssued() {
        Invoice invoice = draft(UUID.randomUUID(), 1, List.of(InvoiceLine.of("Price", Money.of(999, "USD")),
                InvoiceLine.of("Free trial", Money.of(-999, "USD"))));

        // 999 × 5% rounds to 50; -999 × 5% rounds to -50; the tax is zero and adds no line.
        invoice.quoteTax(new TaxQuote(Ratio.of(5, 100), "VAT (5%)"));

        assertThat(invoice.lines()).hasSize(2);
        assertThat(invoice.amount()).isEqualTo(Money.of(0, "USD"));

        invoice.issue(NOW);

        assertThat(invoice.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(invoice.issuedAt()).isEqualTo(NOW);
        assertThat(invoice.paidAt()).isEqualTo(NOW);
        assertThat(invoice.version()).isEqualTo(Invoice.ISSUED_VERSION + 1);
    }

    /** Tax can only be quoted on a draft; an invoice already past that state refuses it. */
    @Test
    void taxCanOnlyBeQuotedOnADraft() {
        Invoice invoice = draft(UUID.randomUUID(), 1, List.of(InvoiceLine.of("Price", Money.of(999, "AED"))));
        invoice.quoteTax(TaxQuote.zero());

        assertThatThrownBy(() -> invoice.quoteTax(TaxQuote.zero()))
                .isInstanceOf(IllegalStateException.class);

        Invoice draft = draft(UUID.randomUUID(), 2, List.of(InvoiceLine.of("Price", Money.of(999, "AED"))));
        assertThatThrownBy(() -> draft.quoteTax(null))
                .isInstanceOf(NullPointerException.class);
    }

    /** An invoice can only be issued from TAX_QUOTED; draft and pending invoices refuse it. */
    @Test
    void anInvoiceCanOnlyBeIssuedFromTaxQuoted() {
        Invoice draft = draft(UUID.randomUUID(), 1, List.of(InvoiceLine.of("Price", Money.of(999, "AED"))));
        assertThatThrownBy(() -> draft.issue(NOW))
                .isInstanceOf(IllegalStateException.class);

        Invoice pending = issue(UUID.randomUUID(), 2, List.of(InvoiceLine.of("Price", Money.of(999, "AED"))));
        assertThat(pending.status()).isEqualTo(InvoiceStatus.PAYMENT_PENDING);
        assertThatThrownBy(() -> pending.issue(NOW))
                .isInstanceOf(IllegalStateException.class);
    }
}
