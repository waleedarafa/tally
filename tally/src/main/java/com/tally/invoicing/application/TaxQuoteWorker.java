package com.tally.invoicing.application;

import com.tally.account.AccountApi;
import com.tally.account.AccountView;
import com.tally.invoicing.InvoiceIssued;
import com.tally.invoicing.InvoicePaid;
import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.domain.Invoice;
import com.tally.invoicing.domain.Invoices;
import com.tally.invoicing.domain.TaxQuote;
import com.tally.invoicing.domain.TaxRates;
import com.tally.invoicing.domain.TaxRequest;
import com.tally.invoicing.domain.TaxUnavailableException;
import com.tally.platform.DurableQueue;
import com.tally.platform.DurableQueues;
import com.tally.platform.Outcome;
import com.tally.platform.QueueDefinition;
import com.tally.platform.QueueHandler;
import com.tally.platform.QueueItem;
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
 * Retries an invoice that could not be taxed (ADR-050). One item per invoice, on a dedicated money queue.
 * Tax is asked outside any transaction; only the transition and the events are transactional. Still ineligible
 * when the invoice is gone or no longer DRAFT: a retry changes nothing twice.
 */
@Component
class TaxQuoteWorker implements QueueHandler {

    private static final Logger log = LoggerFactory.getLogger(TaxQuoteWorker.class);

    static final QueueDefinition DEFINITION = QueueDefinition.of("tax-quote", "invoicing.tax_quote_queue",
            "invoicing.invoice_one_period_per_subscription_and_period").asMoneyQueue();

    private final DurableQueue queue;
    private final Invoices invoices;
    private final AccountApi accounts;
    private final TaxRates taxRates;
    private final TaxProperties properties;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final Clock clock;

    TaxQuoteWorker(DurableQueues queues, Invoices invoices, AccountApi accounts, TaxRates taxRates,
            TaxProperties properties, ApplicationEventPublisher events, TransactionTemplate transactions, Clock clock) {
        this.invoices = invoices;
        this.accounts = accounts;
        this.taxRates = taxRates;
        this.properties = properties;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
        // Pollers start only once the application is up, so registering here is safe.
        this.queue = queues.register(DEFINITION, this);
    }

    /** Queues the invoice for a second attempt at quoting tax, due at {@code dueAt}. */
    void enqueue(UUID invoiceId, Instant dueAt) {
        queue.enqueue(invoiceId.toString(), dueAt, "");
    }

    @Override
    public Outcome handle(QueueItem item) {
        UUID invoiceId = UUID.fromString(item.key());
        Optional<Invoice> existing = invoices.find(invoiceId);
        if (existing.isEmpty() || existing.get().status() != InvoiceStatus.DRAFT) {
            return Outcome.done();
        }
        Invoice draft = existing.get();
        AccountView account = accounts.get(draft.accountId()).orElseThrow();
        LocalDate taxPointDate = draft.billedFor() != null ? LocalDate.ofInstant(draft.billedFor(), account.timeZone())
                : draft.periodStart();
        TaxRequest request = new TaxRequest(account.country(), draft.amount().currency(), draft.amount(), taxPointDate);
        TaxQuote quote;
        // A TaxQuoteRefusedException is not caught: it marks the item FAILED, which pages (MoneyQueueItemsFailed).
        try {
            quote = taxRates.quote(request);
        } catch (TaxUnavailableException e) {
            log.info("Tax unavailable for invoice {}; the item comes back", invoiceId, e);
            return Outcome.retryAt(clock.instant().plus(properties.retryDelay()));
        }
        transactions.executeWithoutResult(tx -> {
            Invoice fresh = invoices.find(invoiceId).orElse(null);
            if (fresh == null || fresh.status() != InvoiceStatus.DRAFT) {
                return;
            }
            fresh.quoteTax(quote);
            Instant now = clock.instant();
            fresh.issue(now);
            invoices.update(fresh);
            events.publishEvent(new InvoiceIssued(UUID.randomUUID(), now, fresh.accountId(), fresh.id(),
                    fresh.paymentReference(), fresh.amount(), fresh.version()));
            if (fresh.status() == InvoiceStatus.PAID) {
                events.publishEvent(new InvoicePaid(UUID.randomUUID(), now, fresh.accountId(), fresh.id(),
                        fresh.paymentReference(), fresh.version()));
            }
        });
        return Outcome.done();
    }
}
