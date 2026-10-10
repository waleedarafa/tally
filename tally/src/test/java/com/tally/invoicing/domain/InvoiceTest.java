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

class InvoiceTest {

    static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    static final PricingReason REASON = new PricingReason("BASIC", 1, "fixed-price-phase", Map.of());

    static Invoice issue(UUID subscription, int period, List<InvoiceLine> lines) {
        return Invoice.issue(UUID.randomUUID(), subscription, "BASIC", period, lines, REASON,
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-02-15"), NOW, NOW);
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
}
