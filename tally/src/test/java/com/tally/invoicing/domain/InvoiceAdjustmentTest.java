package com.tally.invoicing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.PricingReason;
import com.tally.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The adjustment invoice (I2, as amended by ADR-040): a separate document, with its own ID, tied to
 * the period invoice it bills for. I2's identity (subscription + period number) stays for period
 * invoices; the adjustment's identity is the period invoice and a sequence for the period.
 */
class InvoiceAdjustmentTest {

    static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    static final PricingReason REASON = new PricingReason("BASIC", 1, "fixed-price-phase", Map.of());
    static final PricingReason NEW_REASON = new PricingReason("PREMIUM", 2, "fixed-price-phase", Map.of());

    static Invoice periodInvoice(UUID subscription) {
        return Invoice.issue(UUID.randomUUID(), subscription, "BASIC", 2,
                List.of(InvoiceLine.of("Price", Money.of(3699, "AED"))), REASON,
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-02-15"), NOW, NOW);
    }

    @Test
    void anAdjustmentSharesThePeriodInvoiceAccountSubscriptionPeriodAndDates() {
        UUID subscription = UUID.randomUUID();
        Invoice period = periodInvoice(subscription);

        Invoice adjustment = Invoice.issueAdjustment(period, 1, "PREMIUM",
                List.of(InvoiceLine.of("Charge, 14 of 30 days", Money.of(1500, "AED"))), NEW_REASON, NOW, NOW);

        assertThat(adjustment.id()).isNotEqualTo(period.id());
        assertThat(adjustment.accountId()).isEqualTo(period.accountId());
        assertThat(adjustment.subscriptionId()).isEqualTo(period.subscriptionId());
        assertThat(adjustment.planId()).isEqualTo("PREMIUM");
        assertThat(adjustment.periodNumber()).isEqualTo(period.periodNumber());
        assertThat(adjustment.periodStart()).isEqualTo(period.periodStart());
        assertThat(adjustment.periodEnd()).isEqualTo(period.periodEnd());
        assertThat(adjustment.invoiceType()).isEqualTo(InvoiceKind.ADJUSTMENT);
        assertThat(adjustment.periodInvoiceId()).isEqualTo(period.id());
        assertThat(adjustment.quoteReason()).isEqualTo(NEW_REASON);
        assertThat(adjustment.offerVersion()).isEqualTo(NEW_REASON.offerVersion());
        assertThat(adjustment.status()).isEqualTo(InvoiceStatus.PAYMENT_PENDING);
        assertThat(adjustment.version()).isEqualTo(Invoice.ISSUED_VERSION);
    }

    /** A period invoice stays the document I2 describes: no tie, PERIOD type. */
    @Test
    void aPeriodInvoiceHasNoTieToAnotherInvoice() {
        Invoice period = periodInvoice(UUID.randomUUID());

        assertThat(period.invoiceType()).isEqualTo(InvoiceKind.PERIOD);
        assertThat(period.periodInvoiceId()).isNull();
    }

    @Test
    void anAdjustmentIdIsAPureFunctionOfThePeriodInvoiceAndTheSequence() {
        Invoice period = periodInvoice(UUID.randomUUID());

        Invoice first = Invoice.issueAdjustment(period, 1, "PREMIUM",
                List.of(InvoiceLine.of("Charge", Money.of(1500, "AED"))), NEW_REASON, NOW, NOW);
        Invoice retry = Invoice.issueAdjustment(period, 1, "PREMIUM",
                List.of(InvoiceLine.of("Charge", Money.of(1500, "AED"))), NEW_REASON, NOW, NOW);

        // The same period invoice and sequence spell the same document, so a retry issues the same one (D1).
        assertThat(retry.id()).isEqualTo(first.id()).isEqualTo(Invoice.idOfAdjustment(period.id(), 1));
        // A different sequence is a different adjustment.
        assertThat(Invoice.idOfAdjustment(period.id(), 2)).isNotEqualTo(first.id());
        // A different period invoice is a different adjustment.
        assertThat(Invoice.idOfAdjustment(periodInvoice(UUID.randomUUID()).id(), 1)).isNotEqualTo(first.id());
        // An adjustment is never the period invoice's ID.
        assertThat(first.id()).isNotEqualTo(period.id());
    }

    /** A zero adjustment is paid when issued, like a zero period invoice: nothing to collect. */
    @Test
    void aZeroAdjustmentIsPaidWhenIssued() {
        Invoice period = periodInvoice(UUID.randomUUID());

        Invoice adjustment = Invoice.issueAdjustment(period, 1, "PREMIUM",
                List.of(InvoiceLine.of("Price", Money.of(999, "USD")),
                        InvoiceLine.of("Discount", Money.of(-999, "USD"))), REASON, NOW, NOW);

        assertThat(adjustment.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(adjustment.paidAt()).isEqualTo(NOW);
        assertThat(adjustment.version()).isEqualTo(2);
    }

    /** Owing the customer money is a credit note's job, never an invoice's (I1); the adjustment is no exception. */
    @Test
    void aNegativeTotalAdjustmentIsRefused() {
        Invoice period = periodInvoice(UUID.randomUUID());

        assertThatThrownBy(() -> Invoice.issueAdjustment(period, 1, "PREMIUM",
                List.of(InvoiceLine.of("Price", Money.of(999, "USD")),
                        InvoiceLine.of("Credit", Money.of(-1500, "USD"))), REASON, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("less than zero");
    }

    @Test
    void aSequenceBelowOneIsRefused() {
        Invoice period = periodInvoice(UUID.randomUUID());

        assertThatThrownBy(() -> Invoice.issueAdjustment(period, 0, "PREMIUM",
                List.of(InvoiceLine.of("Charge", Money.of(999, "USD"))), REASON, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequence starts at 1");
    }
}
