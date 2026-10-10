package com.tally.invoicing.application;

import com.tally.offers.BillingPeriod;
import com.tally.offers.CatalogApi;
import com.tally.invoicing.InvoiceIssued;
import com.tally.invoicing.domain.Invoice;
import com.tally.invoicing.domain.Invoices;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Issues one invoice per account and billing date (D1), and publishes InvoiceIssued with it. */
@Component
class InvoiceIssuer {

    private final Invoices invoices;
    private final CatalogApi catalog;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    InvoiceIssuer(Invoices invoices, CatalogApi catalog, ApplicationEventPublisher events, Clock clock) {
        this.invoices = invoices;
        this.catalog = catalog;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Issues the invoice for {@code [periodStart, periodEnd)}, due at {@code billedFor}, priced at
     * today's list price. Empty if the account already has an invoice for that billing date. The
     * insert is atomic on the unique constraint (account, period start), so a concurrent duplicate
     * waits, then issues nothing and does not fail (FR-028).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Invoice> issue(UUID accountId, UUID subscriptionId, String planId, LocalDate periodStart,
            LocalDate periodEnd, Instant billedFor) {
        var price = catalog.quote(accountId, planId, BillingPeriod.MONTHLY).price();
        Invoice invoice = Invoice.issue(accountId, subscriptionId, planId, price, periodStart, periodEnd, billedFor,
                clock.instant());
        if (!invoices.insertIfAbsent(invoice)) {
            return Optional.empty();
        }
        events.publishEvent(new InvoiceIssued(UUID.randomUUID(), clock.instant(), invoice.accountId(), invoice.id(),
                invoice.paymentReference(), invoice.amount()));
        return Optional.of(invoice);
    }
}
