package com.tally.invoicing.application;

import com.tally.catalog.BillingPeriod;
import com.tally.catalog.CatalogApi;
import com.tally.invoicing.InvoiceIssued;
import com.tally.invoicing.domain.Invoice;
import com.tally.invoicing.domain.InvoiceRepository;
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

    private final InvoiceRepository invoices;
    private final CatalogApi catalog;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    InvoiceIssuer(InvoiceRepository invoices, CatalogApi catalog, ApplicationEventPublisher events, Clock clock) {
        this.invoices = invoices;
        this.catalog = catalog;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Issues the invoice for {@code [periodStart, periodEnd)}, due at {@code billedFor}, priced at
     * today's list price. Empty if the account already has an invoice for that billing date. The
     * unique constraint on (account, period start) guards against a concurrent issue.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Invoice> issue(UUID accountId, UUID subscriptionId, String planId, LocalDate periodStart,
            LocalDate periodEnd, Instant billedFor) {
        if (invoices.existsByAccountIdAndPeriodStart(accountId, periodStart)) {
            return Optional.empty();
        }
        var price = catalog.quote(accountId, planId, BillingPeriod.MONTHLY).price();
        Invoice invoice = invoices.saveAndFlush(
                Invoice.issue(accountId, subscriptionId, planId, price, periodStart, periodEnd, billedFor,
                        clock.instant()));
        events.publishEvent(new InvoiceIssued(UUID.randomUUID(), clock.instant(), invoice.accountId(), invoice.id(),
                invoice.paymentReference(), invoice.amount()));
        return Optional.of(invoice);
    }
}
