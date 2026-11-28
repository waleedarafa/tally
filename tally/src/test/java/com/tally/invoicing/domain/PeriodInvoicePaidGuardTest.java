package com.tally.invoicing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tally.invoicing.PricingReason;
import com.tally.invoicing.PeriodInvoiceUnpaidException;
import com.tally.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PeriodInvoicePaidGuardTest {

    static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    static final PricingReason REASON = new PricingReason("BASIC", 1, "fixed-price-phase", Map.of());

    static Invoice issue(UUID subscription, int period, List<InvoiceLine> lines) {
        return Invoice.issue(UUID.randomUUID(), subscription, "BASIC", period, lines, REASON,
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-02-15"), NOW, NOW);
    }

    @Test
    void aPaidInvoiceIsReturned() {
        UUID subscription = UUID.randomUUID();
        Invoice invoice = issue(subscription, 1, List.of(InvoiceLine.of("Price", Money.of(999, "USD"))));
        invoice.paid(NOW);

        assertThat(PeriodInvoicePaidGuard.requirePaid(Optional.of(invoice), subscription, 1))
                .isSameAs(invoice);
    }

    @Test
    void aZeroInvoiceIsPaidWhenIssuedAndReturned() {
        UUID subscription = UUID.randomUUID();
        Invoice invoice = issue(subscription, 1, List.of(InvoiceLine.of("Price", Money.of(999, "USD")),
                InvoiceLine.of("Free trial", Money.of(-999, "USD"))));

        assertThat(PeriodInvoicePaidGuard.requirePaid(Optional.of(invoice), subscription, 1))
                .isSameAs(invoice);
    }

    @Test
    void aPaymentPendingInvoiceIsRefused() {
        UUID subscription = UUID.randomUUID();
        Invoice invoice = issue(subscription, 1, List.of(InvoiceLine.of("Price", Money.of(999, "USD"))));

        assertThatThrownBy(() -> PeriodInvoicePaidGuard.requirePaid(Optional.of(invoice), subscription, 1))
                .isInstanceOf(PeriodInvoiceUnpaidException.class)
                .hasMessageContaining("subscription " + subscription)
                .hasMessageContaining("Period 1");
    }

    @Test
    void aPaymentFailedInvoiceIsRefused() {
        UUID subscription = UUID.randomUUID();
        Invoice invoice = issue(subscription, 1, List.of(InvoiceLine.of("Price", Money.of(999, "USD"))));
        invoice.paymentFailed();

        assertThatThrownBy(() -> PeriodInvoicePaidGuard.requirePaid(Optional.of(invoice), subscription, 1))
                .isInstanceOf(PeriodInvoiceUnpaidException.class);
    }

    @Test
    void anEmptyOptionalIsRefused() {
        UUID subscription = UUID.randomUUID();

        assertThatThrownBy(() -> PeriodInvoicePaidGuard.requirePaid(Optional.empty(), subscription, 1))
                .isInstanceOf(PeriodInvoiceUnpaidException.class);
    }
}
