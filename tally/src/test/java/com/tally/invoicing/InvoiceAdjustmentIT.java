package com.tally.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.AbstractIntegrationTest;
import com.tally.platform.InvariantCheck;
import com.tally.invoicing.domain.Invoice;
import com.tally.invoicing.domain.InvoiceLine;
import com.tally.invoicing.domain.Invoices;
import com.tally.invoicing.domain.InvoiceKind;
import com.tally.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AT-12, the adjustment case (ADR-040): the daily duplicate-invoices check must not count an adjustment
 * as a duplicate of its period invoice. It boots the app, so V46 is applied and ddl-auto validate runs.
 * A retry of the same adjustment inserts nothing; two period invoices for overlapping periods on one
 * subscription are still a violation. Overlapping period invoices of two subscriptions of the same
 * account are not (ADR-046).
 */
class InvoiceAdjustmentIT extends AbstractIntegrationTest {

    static final PricingReason REASON = new PricingReason("BASIC", 1, "fixed-price-phase", Map.of());

    @Autowired
    Invoices invoices;

    @Autowired
    @Qualifier("duplicateInvoices")
    InvariantCheck duplicateInvoices;

    @Autowired
    TransactionTemplate transactions;

    static Invoice periodInvoice(UUID account, UUID subscription, int periodNumber,
            LocalDate start, LocalDate end, Instant now) {
        return Invoice.issue(account, subscription, "BASIC", periodNumber,
                List.of(InvoiceLine.of("Price", Money.of(1000, "USD"))), REASON, start, end, now, now);
    }

    @Test
    void anAdjustmentForAPeriodInvoiceIsNotFlagged() {
        UUID account = UUID.randomUUID();
        UUID subscription = UUID.randomUUID();
        Invoice period = periodInvoice(account, subscription, 1,
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-02-15"), clock.instant());
        Invoice adjustment = Invoice.issueAdjustment(period, 1, "PREMIUM",
                List.of(InvoiceLine.of("Charge, 14 of 30 days", Money.of(1500, "USD"))),
                new PricingReason("PREMIUM", 2, "fixed-price-phase", Map.of()), clock.instant(), clock.instant());

        transactions.executeWithoutResult(tx -> {
            assertThat(invoices.addIfAbsent(period)).isTrue();
            assertThat(invoices.addIfAbsent(adjustment)).isTrue();
        });

        Invoice loaded = invoices.find(adjustment.id()).orElseThrow();
        assertThat(loaded.invoiceType()).isEqualTo(InvoiceKind.ADJUSTMENT);
        assertThat(loaded.periodInvoiceId()).isEqualTo(period.id());
        assertThat(loaded.periodNumber()).isEqualTo(period.periodNumber());
        assertThat(loaded.periodStart()).isEqualTo(period.periodStart());
        assertThat(loaded.periodEnd()).isEqualTo(period.periodEnd());

        // Retrying the same document inserts nothing.
        transactions.executeWithoutResult(tx -> {
            assertThat(invoices.addIfAbsent(period)).isFalse();
            assertThat(invoices.addIfAbsent(adjustment)).isFalse();
        });

        // The period invoice and its adjustment, overlapping dates, same account: not a violation.
        assertThat(duplicateInvoices.violations()).isZero();

        // Two period invoices of different subscriptions of one account may overlap (ADR-046).
        UUID sharedAccount = UUID.randomUUID();
        Invoice firstOfAccount = periodInvoice(sharedAccount, UUID.randomUUID(), 1,
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-02-15"), clock.instant());
        Invoice secondOfAccount = periodInvoice(sharedAccount, UUID.randomUUID(), 1,
                LocalDate.parse("2026-01-20"), LocalDate.parse("2026-02-20"), clock.instant());
        transactions.executeWithoutResult(tx -> {
            assertThat(invoices.addIfAbsent(firstOfAccount)).isTrue();
            assertThat(invoices.addIfAbsent(secondOfAccount)).isTrue();
        });
        assertThat(duplicateInvoices.violations()).isZero();

        // Two overlapping period invoices of one subscription are still a violation.
        UUID oneSubscription = UUID.randomUUID();
        Invoice firstOfSub = periodInvoice(UUID.randomUUID(), oneSubscription, 1,
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-02-15"), clock.instant());
        Invoice secondOfSub = periodInvoice(firstOfSub.accountId(), oneSubscription, 2,
                LocalDate.parse("2026-01-20"), LocalDate.parse("2026-02-20"), clock.instant());
        transactions.executeWithoutResult(tx -> {
            assertThat(invoices.addIfAbsent(firstOfSub)).isTrue();
            assertThat(invoices.addIfAbsent(secondOfSub)).isTrue();
        });
        assertThat(duplicateInvoices.violations()).isEqualTo(1);
    }

    @Test
    void twoAdjustmentsForTheSamePeriodAreNotFlagged() {
        UUID account = UUID.randomUUID();
        UUID subscription = UUID.randomUUID();
        Invoice period = periodInvoice(account, subscription, 1,
                LocalDate.parse("2026-04-01"), LocalDate.parse("2026-05-01"), clock.instant());
        Invoice first = Invoice.issueAdjustment(period, 1, "PREMIUM",
                List.of(InvoiceLine.of("Charge, 15 of 30 days", Money.of(1000, "USD"))),
                new PricingReason("PREMIUM", 2, "fixed-price-phase", Map.of()), clock.instant(), clock.instant());
        Invoice second = Invoice.issueAdjustment(period, 2, "PREMIUM",
                List.of(InvoiceLine.of("Charge, a later change", Money.of(200, "USD"))),
                new PricingReason("PREMIUM", 2, "fixed-price-phase", Map.of()), clock.instant(), clock.instant());

        long before = duplicateInvoices.violations();
        transactions.executeWithoutResult(tx -> {
            assertThat(invoices.addIfAbsent(period)).isTrue();
            assertThat(invoices.addIfAbsent(first)).isTrue();
            assertThat(invoices.addIfAbsent(second)).isTrue();
        });

        assertThat(duplicateInvoices.violations()).isEqualTo(before);
    }
}
