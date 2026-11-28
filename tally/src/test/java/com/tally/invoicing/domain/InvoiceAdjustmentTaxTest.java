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

/**
 * Tax on a mid-period upgrade adjustment invoice (ADR-051): the DRAFT adjustment goes through the
 * same draft -> quoteTax -> issue path as a period invoice, then is paid after the charge succeeds.
 */
class InvoiceAdjustmentTaxTest {

    static final Instant NOW = Instant.parse("2026-04-16T10:00:00Z");
    static final TaxQuote VAT = new TaxQuote(Ratio.of(5, 100), "VAT (5%)");

    final UUID accountId = UUID.randomUUID();
    final UUID subscriptionId = UUID.randomUUID();

    Invoice periodInvoice() {
        return Invoice.issue(accountId, subscriptionId, "BASIC", 1,
                List.of(InvoiceLine.of("Price", Money.of(1200, "AED"))),
                new PricingReason("BASIC", 1, "fixed-price-phase", Map.of()),
                LocalDate.parse("2026-04-01"), LocalDate.parse("2026-05-01"), NOW, NOW);
    }

    @Test
    void aDraftAdjustmentIsQuotedTaxedIssuedAndPaid() {
        Invoice period = periodInvoice();

        Invoice adjustment = Invoice.draftAdjustment(period, 1, "PRO",
                List.of(InvoiceLine.of("Premium remaining", Money.of(1000, "AED"))),
                new PricingReason("PRO", 2, "proration", Map.of()), NOW);

        assertThat(adjustment.status()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(adjustment.issuedAt()).isNull();
        assertThat(adjustment.version()).isZero();
        assertThat(adjustment.id()).isEqualTo(Invoice.idOfAdjustment(period.id(), 1));
        assertThat(adjustment.invoiceType()).isEqualTo(InvoiceKind.ADJUSTMENT);
        assertThat(adjustment.periodInvoiceId()).isEqualTo(period.id());
        assertThat(adjustment.paymentReference()).isEqualTo("INV-" + adjustment.id());
        assertThat(adjustment.planId()).isEqualTo("PRO");

        adjustment.quoteTax(VAT);

        assertThat(adjustment.status()).isEqualTo(InvoiceStatus.TAX_QUOTED);
        assertThat(adjustment.amount()).isEqualTo(Money.of(1050, "AED"));
        assertThat(adjustment.lines()).hasSize(2);
        assertThat(adjustment.lines().get(0).amount()).isEqualTo(Money.of(1000, "AED"));
        assertThat(adjustment.lines().get(1).description()).isEqualTo("VAT (5%)");
        assertThat(adjustment.lines().get(1).amount()).isEqualTo(Money.of(50, "AED"));

        adjustment.issue(NOW);

        assertThat(adjustment.status()).isEqualTo(InvoiceStatus.PAYMENT_PENDING);
        assertThat(adjustment.issuedAt()).isEqualTo(NOW);
        assertThat(adjustment.version()).isEqualTo(1);

        adjustment.paid(NOW);

        assertThat(adjustment.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(adjustment.paidAt()).isEqualTo(NOW);
    }

    @Test
    void aZeroRateAddsNoTaxLine() {
        Invoice period = periodInvoice();

        Invoice adjustment = Invoice.draftAdjustment(period, 1, "PRO",
                List.of(InvoiceLine.of("Premium remaining", Money.of(1000, "AED"))),
                new PricingReason("PRO", 2, "proration", Map.of()), NOW);

        adjustment.quoteTax(TaxQuote.zero());
        adjustment.issue(NOW);

        assertThat(adjustment.amount()).isEqualTo(Money.of(1000, "AED"));
        assertThat(adjustment.lines()).hasSize(1);
    }

    @Test
    void rejectsAnEmptyLineListAndANegativeTotal() {
        Invoice period = periodInvoice();

        assertThatThrownBy(() -> Invoice.draftAdjustment(period, 1, "PRO", List.of(),
                new PricingReason("PRO", 2, "proration", Map.of()), NOW))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Invoice.draftAdjustment(period, 1, "PRO",
                List.of(InvoiceLine.of("Premium remaining", Money.of(-1, "AED"))),
                new PricingReason("PRO", 2, "proration", Map.of()), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void twoLinesAreTaxedPerLine() {
        Invoice period = periodInvoice();

        Invoice adjustment = Invoice.draftAdjustment(period, 1, "PRO",
                List.of(InvoiceLine.of("Line one", Money.of(333, "AED")),
                        InvoiceLine.of("Line two", Money.of(333, "AED"))),
                new PricingReason("PRO", 2, "proration", Map.of()), NOW);

        adjustment.quoteTax(VAT);

        assertThat(adjustment.lines()).hasSize(3);
        assertThat(adjustment.lines().get(2).description()).isEqualTo("VAT (5%)");
        assertThat(adjustment.lines().get(2).amount()).isEqualTo(Money.of(34, "AED"));
        assertThat(adjustment.amount()).isEqualTo(Money.of(700, "AED"));
    }
}
