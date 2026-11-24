package com.tally.invoicing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.account.AccountApi;
import com.tally.account.AccountView;
import com.tally.account.Standing;
import com.tally.invoicing.InvoiceIssued;
import com.tally.invoicing.InvoicePaid;
import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.PricingReason;
import com.tally.invoicing.domain.Invoice;
import com.tally.invoicing.domain.InvoiceLine;
import com.tally.invoicing.domain.Invoices;
import com.tally.invoicing.domain.TaxQuote;
import com.tally.invoicing.domain.TaxRates;
import com.tally.invoicing.domain.TaxRequest;
import com.tally.invoicing.domain.TaxUnavailableException;
import com.tally.offers.ChangeRequest;
import com.tally.offers.Period;
import com.tally.offers.PriceLine;
import com.tally.offers.PriceQuote;
import com.tally.offers.Pricing;
import com.tally.offers.QuoteReason;
import com.tally.offers.QuoteRequest;
import com.tally.platform.ClaimGate;
import com.tally.platform.DurableQueue;
import com.tally.platform.DurableQueues;
import com.tally.platform.Outcome;
import com.tally.platform.QueueDefinition;
import com.tally.platform.QueueHandler;
import com.tally.platform.QueueItem;
import com.tally.shared.Money;
import com.tally.shared.Ratio;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.junit.jupiter.api.Test;

/**
 * Plain unit tests of the tax flow (ADR-050), with tiny in-memory doubles the way UpgradeCommandTest
 * does. No Spring context: no database, no queue, no clock of its own beyond the injected one.
 */
class TaxQuoteFlowTest {

    static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");
    static final LocalDate PERIOD_START = LocalDate.parse("2026-11-01");
    static final LocalDate PERIOD_END = LocalDate.parse("2026-12-01");
    static final Duration RETRY_DELAY = Duration.ofMinutes(5);
    static final ZoneId UTC = ZoneId.of("UTC");

    static final class FakeInvoices implements Invoices {
        final Map<UUID, Invoice> byId = new LinkedHashMap<>();
        int addIfAbsentCalls = 0;
        int updateCalls = 0;

        void add(Invoice invoice) { byId.put(invoice.id(), invoice); }

        @Override public boolean addIfAbsent(Invoice invoice) {
            addIfAbsentCalls++;
            if (byId.containsKey(invoice.id())) { return false; }
            byId.put(invoice.id(), invoice);
            return true;
        }

        @Override public Optional<Invoice> find(UUID invoiceId) { return Optional.ofNullable(byId.get(invoiceId)); }

        @Override public Optional<Invoice> forPaymentReference(String paymentReference) { return Optional.empty(); }

        @Override public void update(Invoice invoice) { updateCalls++; byId.put(invoice.id(), invoice); }

        @Override public List<Invoice> ofAccount(UUID accountId) { return List.of(); }

        @Override public List<Invoice> ofSubscription(UUID subscriptionId) { return List.of(); }

        @Override public List<Invoice> issuedBetween(Instant from, Instant to) { return List.of(); }

        @Override public List<Invoice> adjustmentsOf(UUID periodInvoiceId) { return List.of(); }
    }

    static final class FakeAccounts implements AccountApi {
        private final AccountView account;

        FakeAccounts(AccountView account) { this.account = account; }

        @Override public Optional<AccountView> get(UUID accountId) { return Optional.of(account); }

        @Override public void recordPaymentToken(UUID accountId, String paymentToken) {}

        @Override public boolean setBillingDayFrom(UUID accountId, Instant startedAt) { return false; }
    }

    static final class FakePricing implements Pricing {
        private final PriceQuote quote;

        FakePricing(PriceQuote quote) { this.quote = quote; }

        @Override public PriceQuote quote(QuoteRequest request) { return quote; }

        @Override public PriceQuote quoteChange(ChangeRequest request) { throw new UnsupportedOperationException(); }
    }

    static final class FakeTaxRates implements TaxRates {
        private final TaxQuote quote;
        private final TaxUnavailableException exception;

        int calls = 0;
        boolean transactionActiveInsideQuote = false;

        FakeTaxRates(TaxQuote quote) { this.quote = quote; this.exception = null; }

        FakeTaxRates(TaxUnavailableException exception) { this.quote = null; this.exception = exception; }

        @Override public TaxQuote quote(TaxRequest request) {
            calls++;
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                transactionActiveInsideQuote = true;
            }
            if (exception != null) { throw exception; }
            return quote;
        }
    }

    static final class FakeEvents implements ApplicationEventPublisher {
        final List<Object> published = new ArrayList<>();

        @Override public void publishEvent(Object event) { published.add(event); }
    }

    static final class FakeQueue implements DurableQueue {
        final List<String> keys = new ArrayList<>();
        final List<Instant> dueAts = new ArrayList<>();

        @Override public String name() { return "tax-quote"; }

        @Override public boolean enqueue(String key, Instant dueAt, String payload) {
            keys.add(key);
            dueAts.add(dueAt);
            return true;
        }

        @Override public int processDue() { return 0; }
    }

    static final class FakeQueues implements DurableQueues {
        final FakeQueue queue = new FakeQueue();

        @Override public DurableQueue register(QueueDefinition definition, QueueHandler handler) { return queue; }

        @Override public DurableQueue register(QueueDefinition definition, QueueHandler handler, ClaimGate gate) { return queue; }
    }

    static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }

        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override protected void doCommit(DefaultTransactionStatus status) {}

        @Override protected void doRollback(DefaultTransactionStatus status) {}
    }

    static TransactionTemplate transactions() {
        return new TransactionTemplate(new NoOpTransactionManager());
    }

    static TaxProperties properties() {
        return new TaxProperties("", Duration.ofSeconds(1), Duration.ofSeconds(3), RETRY_DELAY, false, Map.of());
    }

    static AccountView account(UUID accountId, String currencyCode, String country) {
        return new AccountView(accountId, Currency.getInstance(currencyCode), UTC, country, Optional.of("tok_123"),
                Standing.GOOD, OptionalInt.empty());
    }

    static PriceQuote quoteOneLine(UUID subscriptionId, long minorUnits, String currencyCode) {
        Money amount = Money.of(minorUnits, currencyCode);
        return new PriceQuote(subscriptionId, "BASIC", 1, new Period(1, PERIOD_START, PERIOD_END),
                List.of(new PriceLine("Price", amount)), amount,
                new QuoteReason("BASIC", 1, "fixed-price-phase", Map.of()));
    }

    static Invoice draft(UUID accountId, UUID subscriptionId, long minorUnits, String currencyCode) {
        return Invoice.draft(accountId, subscriptionId, "BASIC", 1,
                List.of(InvoiceLine.of("Price", Money.of(minorUnits, currencyCode))),
                new PricingReason("BASIC", 1, "fixed-price-phase", Map.of()),
                PERIOD_START, PERIOD_END, NOW);
    }

    static TaxQuoteWorker worker(FakeQueues queues, FakeInvoices invoices, FakeAccounts accounts,
            FakeTaxRates taxRates, FakeEvents events, TransactionTemplate transactions) {
        return new TaxQuoteWorker(queues, invoices, accounts, taxRates, properties(), events, transactions,
                Clock.fixed(NOW, UTC));
    }

    static InvoiceIssuer issuer(FakeQueues queues, FakeInvoices invoices, FakeAccounts accounts,
            FakePricing pricing, FakeTaxRates taxRates, FakeEvents events, TransactionTemplate transactions,
            TaxQuoteWorker worker) {
        return new InvoiceIssuer(invoices, pricing, accounts, taxRates, worker, properties(), events, transactions,
                Clock.fixed(NOW, UTC));
    }

    // --- InvoiceIssuer ---

    @Test
    void theHappyPathQuotesTaxOutsideATransactionAndIssues() {
        UUID accountId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        AccountView account = account(accountId, "AED", "AE");
        PriceQuote quote = quoteOneLine(subscriptionId, 1417, "AED");
        FakeInvoices invoices = new FakeInvoices();
        FakeAccounts accounts = new FakeAccounts(account);
        FakePricing pricing = new FakePricing(quote);
        FakeTaxRates taxRates = new FakeTaxRates(new TaxQuote(Ratio.of(5, 100), "VAT (5%)"));
        FakeEvents events = new FakeEvents();
        FakeQueues queues = new FakeQueues();
        TransactionTemplate transactions = transactions();
        TaxQuoteWorker worker = worker(queues, invoices, accounts, taxRates, events, transactions);
        InvoiceIssuer issuer = issuer(queues, invoices, accounts, pricing, taxRates, events, transactions, worker);

        Optional<Invoice> result = issuer.issue(accountId, subscriptionId, "BASIC", 1, 1, PERIOD_START, PERIOD_END, NOW);

        assertThat(result).isPresent();
        Invoice invoice = result.get();
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.PAYMENT_PENDING);
        assertThat(invoice.issuedAt()).isEqualTo(NOW);
        assertThat(invoice.lines()).hasSize(2);
        assertThat(invoice.lines().get(1).description()).isEqualTo("VAT (5%)");
        assertThat(invoice.lines().get(1).amount()).isEqualTo(Money.of(71, "AED"));
        assertThat(invoice.amount()).isEqualTo(Money.of(1488, "AED"));
        assertThat(invoice.version()).isEqualTo(1L);
        assertThat(events.published).filteredOn(e -> e instanceof InvoiceIssued).hasSize(1);
        assertThat(events.published).filteredOn(e -> e instanceof InvoiceIssued).first()
                .satisfies(e -> assertThat(((InvoiceIssued) e).invoiceId()).isEqualTo(invoice.id()));
        assertThat(events.published).noneMatch(e -> e instanceof InvoicePaid);
        assertThat(queues.queue.keys).isEmpty();
        assertThat(taxRates.calls).isEqualTo(1);
        assertThat(taxRates.transactionActiveInsideQuote).isFalse();
    }

    @Test
    void whenTaxIsUnavailableTheInvoiceWaitsDraftedAndIsEnqueued() {
        UUID accountId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        AccountView account = account(accountId, "AED", "AE");
        FakeInvoices invoices = new FakeInvoices();
        FakeAccounts accounts = new FakeAccounts(account);
        FakePricing pricing = new FakePricing(quoteOneLine(subscriptionId, 1417, "AED"));
        FakeTaxRates taxRates = new FakeTaxRates(new TaxUnavailableException("service down"));
        FakeEvents events = new FakeEvents();
        FakeQueues queues = new FakeQueues();
        TransactionTemplate transactions = transactions();
        TaxQuoteWorker worker = worker(queues, invoices, accounts, taxRates, events, transactions);
        InvoiceIssuer issuer = issuer(queues, invoices, accounts, pricing, taxRates, events, transactions, worker);

        Optional<Invoice> result = issuer.issue(accountId, subscriptionId, "BASIC", 1, 1, PERIOD_START, PERIOD_END, NOW);

        assertThat(result).isPresent();
        Invoice invoice = result.get();
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(invoice.issuedAt()).isNull();
        assertThat(invoice.version()).isZero();
        assertThat(invoice.lines()).hasSize(1);
        assertThat(invoices.find(invoice.id())).isPresent();
        assertThat(events.published).noneMatch(e -> e instanceof InvoiceIssued);
        assertThat(events.published).noneMatch(e -> e instanceof InvoicePaid);
        assertThat(queues.queue.keys).containsExactly(invoice.id().toString());
        assertThat(queues.queue.dueAts).containsExactly(NOW.plus(RETRY_DELAY));
        assertThat(taxRates.calls).isEqualTo(1);
        assertThat(taxRates.transactionActiveInsideQuote).isFalse();
    }

    @Test
    void aDuplicateInvoiceAlreadyInTheDbDuringTheOutageIsNotEnqueuedTwice() {
        UUID accountId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        AccountView account = account(accountId, "AED", "AE");
        FakeInvoices invoices = new FakeInvoices();
        invoices.add(draft(accountId, subscriptionId, 1417, "AED"));
        FakeAccounts accounts = new FakeAccounts(account);
        FakePricing pricing = new FakePricing(quoteOneLine(subscriptionId, 1417, "AED"));
        FakeTaxRates taxRates = new FakeTaxRates(new TaxUnavailableException("service down"));
        FakeEvents events = new FakeEvents();
        FakeQueues queues = new FakeQueues();
        TransactionTemplate transactions = transactions();
        TaxQuoteWorker worker = worker(queues, invoices, accounts, taxRates, events, transactions);
        InvoiceIssuer issuer = issuer(queues, invoices, accounts, pricing, taxRates, events, transactions, worker);

        Optional<Invoice> result = issuer.issue(accountId, subscriptionId, "BASIC", 1, 1, PERIOD_START, PERIOD_END, NOW);

        assertThat(result).isEmpty();
        assertThat(invoices.addIfAbsentCalls).isEqualTo(1);
        assertThat(invoices.byId).hasSize(1);
        assertThat(invoices.find(draft(accountId, subscriptionId, 1417, "AED").id()))
                .hasValueSatisfying(i -> assertThat(i.status()).isEqualTo(InvoiceStatus.DRAFT));
        assertThat(events.published).isEmpty();
        assertThat(queues.queue.keys).isEmpty();
        assertThat(taxRates.calls).isEqualTo(1);
    }

    // --- TaxQuoteWorker ---

    @Test
    void whenTaxIsStillDownTheWorkerRetriesAndLeavesTheInvoiceDrafted() {
        UUID accountId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        AccountView account = account(accountId, "AED", "AE");
        Invoice draft = draft(accountId, subscriptionId, 1417, "AED");
        FakeInvoices invoices = new FakeInvoices();
        invoices.add(draft);
        FakeAccounts accounts = new FakeAccounts(account);
        FakeTaxRates taxRates = new FakeTaxRates(new TaxUnavailableException("service down"));
        FakeEvents events = new FakeEvents();
        FakeQueues queues = new FakeQueues();
        TransactionTemplate transactions = transactions();
        TaxQuoteWorker worker = worker(queues, invoices, accounts, taxRates, events, transactions);

        Outcome outcome = worker.handle(new QueueItem(1, draft.id().toString(), NOW, "", 1));

        assertThat(outcome).isEqualTo(Outcome.retryAt(NOW.plus(RETRY_DELAY)));
        assertThat(invoices.find(draft.id())).hasValueSatisfying(invoice -> {
            assertThat(invoice.status()).isEqualTo(InvoiceStatus.DRAFT);
            assertThat(invoice.issuedAt()).isNull();
            assertThat(invoice.lines()).hasSize(1);
        });
        assertThat(events.published).isEmpty();
        assertThat(invoices.updateCalls).isZero();
        assertThat(taxRates.calls).isEqualTo(1);
        assertThat(taxRates.transactionActiveInsideQuote).isFalse();
    }

    @Test
    void whenTaxRecoversTheWorkerIssuesTheInvoice() {
        UUID accountId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        AccountView account = account(accountId, "SAR", "SA");
        Invoice draft = draft(accountId, subscriptionId, 3000, "SAR");
        FakeInvoices invoices = new FakeInvoices();
        invoices.add(draft);
        FakeAccounts accounts = new FakeAccounts(account);
        FakeTaxRates taxRates = new FakeTaxRates(new TaxQuote(Ratio.of(15, 100), "VAT (15%)"));
        FakeEvents events = new FakeEvents();
        FakeQueues queues = new FakeQueues();
        TransactionTemplate transactions = transactions();
        TaxQuoteWorker worker = worker(queues, invoices, accounts, taxRates, events, transactions);

        Outcome outcome = worker.handle(new QueueItem(1, draft.id().toString(), NOW, "", 1));

        assertThat(outcome).isEqualTo(Outcome.done());
        assertThat(invoices.find(draft.id())).hasValueSatisfying(invoice -> {
            assertThat(invoice.status()).isEqualTo(InvoiceStatus.PAYMENT_PENDING);
            assertThat(invoice.issuedAt()).isEqualTo(NOW);
            assertThat(invoice.lines()).hasSize(2);
            assertThat(invoice.lines().get(1).description()).isEqualTo("VAT (15%)");
            assertThat(invoice.lines().get(1).amount()).isEqualTo(Money.of(450, "SAR"));
            assertThat(invoice.amount()).isEqualTo(Money.of(3450, "SAR"));
            assertThat(invoice.version()).isEqualTo(1L);
        });
        assertThat(invoices.updateCalls).isEqualTo(1);
        assertThat(events.published).filteredOn(e -> e instanceof InvoiceIssued).hasSize(1);
        assertThat(events.published).noneMatch(e -> e instanceof InvoicePaid);
        assertThat(events.published).filteredOn(e -> e instanceof InvoiceIssued).first()
                .satisfies(e -> {
                    assertThat(((InvoiceIssued) e).invoiceId()).isEqualTo(draft.id());
                    assertThat(((InvoiceIssued) e).amount()).isEqualTo(Money.of(3450, "SAR"));
                    assertThat(((InvoiceIssued) e).sequence()).isEqualTo(1L);
                });
        assertThat(taxRates.calls).isEqualTo(1);
        assertThat(taxRates.transactionActiveInsideQuote).isFalse();
    }

    @Test
    void theWorkerIsIdempotentForAnAlreadyIssuedOrMissingInvoice() {
        UUID accountId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        AccountView account = account(accountId, "AED", "AE");
        Invoice issued = Invoice.issue(accountId, subscriptionId, "BASIC", 1,
                List.of(InvoiceLine.of("Price", Money.of(999, "AED"))),
                new PricingReason("BASIC", 1, "fixed-price-phase", Map.of()),
                PERIOD_START, PERIOD_END, NOW, NOW);
        FakeInvoices invoices = new FakeInvoices();
        invoices.add(issued);
        FakeAccounts accounts = new FakeAccounts(account);
        FakeTaxRates taxRates = new FakeTaxRates(new TaxQuote(Ratio.of(5, 100), "VAT (5%)"));
        FakeEvents events = new FakeEvents();
        FakeQueues queues = new FakeQueues();
        TransactionTemplate transactions = transactions();
        TaxQuoteWorker worker = worker(queues, invoices, accounts, taxRates, events, transactions);

        Outcome outcome = worker.handle(new QueueItem(1, issued.id().toString(), NOW, "", 1));

        assertThat(outcome).isEqualTo(Outcome.done());
        Outcome missing = worker.handle(new QueueItem(2, UUID.randomUUID().toString(), NOW, "", 1));

        assertThat(missing).isEqualTo(Outcome.done());
        assertThat(taxRates.calls).isZero();
        assertThat(events.published).isEmpty();
        assertThat(invoices.updateCalls).isZero();
        assertThat(invoices.find(issued.id())).hasValueSatisfying(invoice -> {
            assertThat(invoice.status()).isEqualTo(InvoiceStatus.PAYMENT_PENDING);
            assertThat(invoice.issuedAt()).isEqualTo(NOW);
        });
    }
}
