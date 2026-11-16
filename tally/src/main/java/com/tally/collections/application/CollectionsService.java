package com.tally.collections.application;

import com.tally.collections.CaseStatus;
import com.tally.collections.CaseView;
import com.tally.collections.Collections;
import com.tally.collections.domain.CollectionCase;
import com.tally.collections.domain.CollectionCases;
import com.tally.invoicing.InvoicePaid;
import com.tally.invoicing.InvoicePaymentDeclined;
import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.InvoicingApi;
import com.tally.platform.DurableQueue;
import com.tally.platform.DurableQueues;
import com.tally.platform.Outcome;
import com.tally.platform.QueueDefinition;
import com.tally.platform.QueueHandler;
import com.tally.platform.QueueItem;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Collections, day 3 only (Slice 1R PR-8; ADR-005; design.md §5). One case per account covers every unpaid invoice
 * (Slice 3 PR-3): a declined invoice payment joins the open case, or opens one and schedules its day-3 retry on
 * Collections' own queue (a claimed row with a due time). The retry asks Invoicing to request payment again for each
 * unpaid invoice of the case. The case closes when none is unpaid.
 */
@Service
class CollectionsService implements Collections, QueueHandler {

    private static final Logger log = LoggerFactory.getLogger(CollectionsService.class);

    /** Design.md §5: the first retry, on day 3 after the decline. Days 7, 10 and 30 come in a later slice. */
    static final Duration DAY_THREE = Duration.ofDays(3);

    /** A decline finds the open case closed while it waits only if the last invoice is paid at that moment. */
    static final int JOIN_ATTEMPTS = 3;

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
     * The invoice joins the account's open case, or opens one (Slice 3: one case covers every unpaid invoice). The
     * case opens with an atomic insert, and only the call that opens it schedules its day-3 retry, in the same
     * transaction. A repeated decline adds nothing. A decline of an invoice Invoicing says is paid (a publication
     * resubmitted late) adds nothing: the case covers unpaid invoices only, and only {@code InvoicePaid} moves it back.
     */
    @ApplicationModuleListener
    void on(InvoicePaymentDeclined declined) {
        if (paid(declined.accountId(), declined.invoiceId())) {
            log.info("Invoice {} of account {} is paid; its decline joins no case", declined.invoiceId(),
                    declined.accountId());
            return;
        }
        Instant now = clock.instant();
        for (int attempt = 1; attempt <= JOIN_ATTEMPTS; attempt++) {
            UUID caseId = UUID.randomUUID();
            if (cases.openIfNone(caseId, declined.accountId(), declined.invoiceId(), now)) {
                cases.addInvoice(caseId, declined.invoiceId(), now);
                queue.enqueue(caseId + ":1", declined.occurredAt().plus(DAY_THREE), caseId.toString());
                return;
            }
            Optional<CollectionCase> open = cases.lockOpenCase(declined.accountId());
            if (open.isPresent()) {
                cases.addInvoice(open.get().id(), declined.invoiceId(), now);
                return;
            }
            // The open case closed while this call waited for its lock: open a new one.
        }
        throw new IllegalStateException("Account " + declined.accountId() + ": no open case to join after "
                + JOIN_ATTEMPTS + " attempts");
    }

    private boolean paid(UUID accountId, UUID invoiceId) {
        return invoicing.invoicesFor(accountId).stream()
                .anyMatch(i -> i.id().equals(invoiceId) && i.status() == InvoiceStatus.PAID);
    }

    /**
     * One of the case's invoices is paid: the case closes once none of its invoices is unpaid (design.md §5). Under
     * the case's lock, so a decline that joins at the same time is either in the case before the count, or finds the
     * case closed and opens a new one.
     */
    @ApplicationModuleListener
    void on(InvoicePaid paid) {
        Instant now = clock.instant();
        cases.lockOpenCase(paid.accountId()).ifPresent(open -> {
            cases.markPaid(open.id(), paid.invoiceId(), now);
            if (cases.unpaidOf(open.id()).isEmpty()) {
                open.close(now);
            }
        });
    }

    /**
     * The day-3 retry. Called with no transaction open (FR-005): records retry 1 of the case, then asks
     * Invoicing again for each unpaid invoice of the case, which asks Payment, which may ask the provider. Run
     * again after a crash, it asks again; Payment makes retry 1 of each invoice once.
     */
    @Override
    public Outcome handle(QueueItem item) {
        UUID caseId = UUID.fromString(item.payload());
        List<UUID> unpaid = transactions.execute(tx -> {
            Optional<CollectionCase> found = cases.findById(caseId).filter(c -> c.status() == CaseStatus.OPEN);
            found.ifPresent(c -> cases.recordRetry(c.id(), 1, clock.instant()));
            return found.map(c -> cases.unpaidOf(c.id())).orElse(List.of());
        });
        for (UUID invoiceId : unpaid == null ? List.<UUID>of() : unpaid) {
            invoicing.requestPaymentAgain(invoiceId, 1);
        }
        return Outcome.done();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CaseView> caseFor(UUID accountId) {
        return cases.findByAccountIdOrderByOpenedAtDesc(accountId).stream()
                .sorted((a, b) -> Boolean.compare(b.status() == CaseStatus.OPEN, a.status() == CaseStatus.OPEN))
                .findFirst()
                .map(c -> new CaseView(c.id(), c.accountId(), c.invoiceId(), c.status(), c.openedAt(),
                        c.closedAt(), cases.retriesOf(c.id()), cases.invoicesOf(c.id()), cases.unpaidOf(c.id())));
    }
}
