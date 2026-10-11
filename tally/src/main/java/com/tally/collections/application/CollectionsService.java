package com.tally.collections.application;

import com.tally.collections.CaseStatus;
import com.tally.collections.CaseView;
import com.tally.collections.Collections;
import com.tally.collections.domain.CollectionCase;
import com.tally.collections.domain.CollectionCases;
import com.tally.invoicing.InvoicePaid;
import com.tally.invoicing.InvoicePaymentDeclined;
import com.tally.invoicing.InvoicingApi;
import com.tally.platform.DurableQueue;
import com.tally.platform.DurableQueues;
import com.tally.platform.Outcome;
import com.tally.platform.QueueDefinition;
import com.tally.platform.QueueHandler;
import com.tally.platform.QueueItem;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Collections, day 3 only (Slice 1R PR-8; ADR-005; design.md §5). A declined invoice payment opens one
 * case per account and schedules the day-3 retry on Collections' own queue (a claimed row with a due time).
 * The retry asks Invoicing to request payment again. The invoice paid closes the case.
 */
@Service
class CollectionsService implements Collections, QueueHandler {

    private static final Logger log = LoggerFactory.getLogger(CollectionsService.class);

    /** Design.md §5: the first retry, on day 3 after the decline. Days 7, 10 and 30 come in a later slice. */
    static final Duration DAY_THREE = Duration.ofDays(3);

    // A money queue (FR-037): a retry collects money. The permanent guard is one case_retry row per case and
    // retry number; Payment's own retry number makes a repeated call harmless as well.
    static final QueueDefinition DEFINITION = QueueDefinition.of("collections-retry", "collections.retry_queue",
            "collections.case_retry_one_per_case_and_number").asMoneyQueue();

    private final CollectionCases cases;
    private final InvoicingApi invoicing;
    private final DurableQueue queue;
    private final TransactionTemplate transactions;
    private final Clock clock;

    CollectionsService(CollectionCases cases, InvoicingApi invoicing, DurableQueues queues,
            TransactionTemplate transactions, Clock clock) {
        this.cases = cases;
        this.invoicing = invoicing;
        this.transactions = transactions;
        this.clock = clock;
        this.queue = queues.register(DEFINITION, this);
    }

    /**
     * Opens the account's case unless one is open, with an atomic insert, and schedules its day-3 retry in
     * the same transaction. A duplicate delivery, or a second decline while the case is open, opens nothing.
     */
    @ApplicationModuleListener
    void on(InvoicePaymentDeclined declined) {
        UUID caseId = UUID.randomUUID();
        if (!cases.openIfNone(caseId, declined.accountId(), declined.invoiceId(), clock.instant())) {
            log.info("Account {} already has an open case; invoice {} declined again", declined.accountId(),
                    declined.invoiceId());
            return;
        }
        queue.enqueue(caseId + ":1", declined.occurredAt().plus(DAY_THREE), caseId.toString());
    }

    /** The case's invoice is paid: the case closes (design.md §5). The optimistic lock orders a race. */
    @ApplicationModuleListener
    void on(InvoicePaid paid) {
        cases.findByAccountIdAndInvoiceIdAndStatus(paid.accountId(), paid.invoiceId(), CaseStatus.OPEN)
                .ifPresent(open -> open.close(clock.instant()));
    }

    /**
     * The day-3 retry. Called with no transaction open (FR-005): records retry 1 of the case, then asks
     * Invoicing, which asks Payment, which may ask the provider. Run again after a crash, it asks again;
     * Payment makes retry 1 once.
     */
    @Override
    public Outcome handle(QueueItem item) {
        UUID caseId = UUID.fromString(item.payload());
        Optional<CollectionCase> open = transactions.execute(tx -> {
            Optional<CollectionCase> found = cases.findById(caseId).filter(c -> c.status() == CaseStatus.OPEN);
            found.ifPresent(c -> cases.recordRetry(c.id(), 1, clock.instant()));
            return found;
        });
        if (open == null || open.isEmpty()) {
            return Outcome.done();
        }
        invoicing.requestPaymentAgain(open.get().invoiceId(), 1);
        return Outcome.done();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CaseView> caseFor(UUID accountId) {
        return cases.findByAccountIdOrderByOpenedAtDesc(accountId).stream()
                .sorted((a, b) -> Boolean.compare(b.status() == CaseStatus.OPEN, a.status() == CaseStatus.OPEN))
                .findFirst()
                .map(c -> new CaseView(c.id(), c.accountId(), c.invoiceId(), c.status(), c.openedAt(),
                        c.closedAt(), cases.retriesOf(c.id())));
    }
}
