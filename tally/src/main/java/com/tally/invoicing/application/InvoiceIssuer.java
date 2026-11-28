package com.tally.invoicing.application;

import com.tally.account.AccountApi;
import com.tally.account.AccountView;
import com.tally.invoicing.PricingReason;
import com.tally.invoicing.domain.InvoiceLine;
import com.tally.offers.Period;
import com.tally.offers.PriceQuote;
import com.tally.offers.Pricing;
import com.tally.offers.QuoteRequest;
import com.tally.invoicing.InvoiceIssued;
import com.tally.invoicing.InvoicePaid;
import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.domain.Invoice;
import com.tally.invoicing.domain.Invoices;
import com.tally.invoicing.domain.TaxQuote;
import com.tally.invoicing.domain.TaxRates;
import com.tally.invoicing.domain.TaxRequest;
import com.tally.invoicing.domain.TaxUnavailableException;
import com.tally.invoicing.domain.TaxQuoteRefusedException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issues one invoice per subscription and period (I2, D1), and publishes InvoiceIssued with it. An
 * invoice whose total is zero is paid at once: it also publishes InvoicePaid, and nothing is collected.
 *
 * <p>Tax is quoted outside any transaction (ADR-050): {@link TaxRates} is an external service, and no
 * connection is held open for it. An invoice whose tax cannot be quoted waits in DRAFT and is retried
 * on the tax-quote queue ({@link TaxQuoteWorker}).
 */
@Component
class InvoiceIssuer {

    private static final Logger log = LoggerFactory.getLogger(InvoiceIssuer.class);

    private final Invoices invoices;
    private final Pricing pricing;
    private final AccountApi accounts;
    private final TaxRates taxRates;
    private final TaxQuoteWorker taxQuoteWorker;
    private final TaxProperties taxProperties;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final Clock clock;

    InvoiceIssuer(Invoices invoices, Pricing pricing, AccountApi accounts, TaxRates taxRates,
            TaxQuoteWorker taxQuoteWorker, TaxProperties taxProperties, ApplicationEventPublisher events,
            TransactionTemplate transactions, Clock clock) {
        this.invoices = invoices;
        this.pricing = pricing;
        this.accounts = accounts;
        this.taxRates = taxRates;
        this.taxQuoteWorker = taxQuoteWorker;
        this.taxProperties = taxProperties;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Issues the invoice for period {@code periodNumber}, {@code [periodStart, periodEnd)}, due at
     * {@code billedFor}, with the lines of the PriceQuote for the subscription's offer version (I3;
     * Invoicing applies no offer rule, R2). Empty if the subscription already has its invoice for that
     * period. The insert is atomic on the invoice ID, which encodes subscription and period (I2), so a
     * concurrent duplicate waits, then issues nothing and does not fail (FR-028).
     */
    Optional<Invoice> issue(UUID accountId, UUID subscriptionId, String planId, int offerVersion, int periodNumber,
            LocalDate periodStart, LocalDate periodEnd, Instant billedFor) {
        AccountView account = accounts.get(accountId).orElseThrow();
        PriceQuote quote = pricing.quote(new QuoteRequest(subscriptionId, accountId, planId, offerVersion,
                new Period(periodNumber, periodStart, periodEnd)));
        Invoice invoice = Invoice.draft(accountId, subscriptionId, planId, periodNumber,
                quote.lines().stream().map(line -> InvoiceLine.of(line.description(), line.amount())).toList(),
                new PricingReason(quote.reason().offerId(), quote.reason().offerVersion(), quote.reason().kind(),
                        quote.reason().flags()),
                periodStart, periodEnd, billedFor);
        TaxRequest taxRequest = new TaxRequest(account.country(), invoice.amount().currency(), invoice.amount(),
                LocalDate.ofInstant(billedFor, account.timeZone()));
        TaxQuote tax;
        try {
            tax = taxRates.quote(taxRequest);
        } catch (TaxUnavailableException | TaxQuoteRefusedException e) {
            log.warn("Tax for invoice {} could not be quoted; it is stored as DRAFT and goes to the tax-quote queue",
                    invoice.id(), e);
            return transactions.execute(tx -> {
                if (!invoices.addIfAbsent(invoice)) {
                    return Optional.empty();
                }
                taxQuoteWorker.enqueue(invoice.id(), clock.instant().plus(taxProperties.retryDelay()));
                return Optional.of(invoice);
            });
        }
        invoice.quoteTax(tax);
        Instant now = clock.instant();
        invoice.issue(now);
        return transactions.execute(tx -> {
            if (!invoices.addIfAbsent(invoice)) {
                return Optional.empty();
            }
            events.publishEvent(new InvoiceIssued(UUID.randomUUID(), now, invoice.accountId(), invoice.id(),
                    invoice.paymentReference(), invoice.amount(), Invoice.ISSUED_VERSION));
            if (invoice.status() == InvoiceStatus.PAID) {
                events.publishEvent(new InvoicePaid(UUID.randomUUID(), now, invoice.accountId(), invoice.id(),
                        invoice.paymentReference(), invoice.version()));
            }
            return Optional.of(invoice);
        });
    }
}
