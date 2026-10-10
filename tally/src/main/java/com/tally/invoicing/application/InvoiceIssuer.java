package com.tally.invoicing.application;

import com.tally.invoicing.domain.InvoiceLine;
import com.tally.offers.Period;
import com.tally.offers.PriceQuote;
import com.tally.offers.Pricing;
import com.tally.offers.QuoteRequest;
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
    private final Pricing pricing;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    InvoiceIssuer(Invoices invoices, Pricing pricing, ApplicationEventPublisher events, Clock clock) {
        this.invoices = invoices;
        this.pricing = pricing;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Issues the invoice for period {@code periodNumber}, {@code [periodStart, periodEnd)}, due at
     * {@code billedFor}, with the lines of the offer's PriceQuote (I3; Invoicing applies no offer rule, R2). Empty if the account already has an invoice for that billing date. The
     * insert is atomic on the unique constraint (account, period start), so a concurrent duplicate
     * waits, then issues nothing and does not fail (FR-028).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Invoice> issue(UUID accountId, UUID subscriptionId, String planId, int periodNumber,
            LocalDate periodStart, LocalDate periodEnd, Instant billedFor) {
        PriceQuote quote = pricing.quote(new QuoteRequest(subscriptionId, accountId, planId,
                new Period(periodNumber, periodStart, periodEnd)));
        Invoice invoice = Invoice.issue(accountId, subscriptionId, planId,
                quote.lines().stream().map(line -> InvoiceLine.of(line.description(), line.amount())).toList(),
                quote.offerVersion(), quote.reason().summary(), periodStart, periodEnd, billedFor, clock.instant());
        if (!invoices.insertIfAbsent(invoice)) {
            return Optional.empty();
        }
        events.publishEvent(new InvoiceIssued(UUID.randomUUID(), clock.instant(), invoice.accountId(), invoice.id(),
                invoice.paymentReference(), invoice.amount()));
        return Optional.of(invoice);
    }
}
