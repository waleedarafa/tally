package com.tally.collections.application;

import com.tally.collections.CaseStatus;
import com.tally.collections.CaseView;
import com.tally.collections.Collections;
import com.tally.collections.PaymentRequestView;
import com.tally.collections.domain.CollectionCase;
import com.tally.collections.domain.CollectionCases;
import com.tally.collections.domain.RetryStep;
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
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Collections (ADR-005; design.md §5, proposed in ADR-037). One case per account covers every unpaid invoice: a
 * declined invoice payment joins the account's case with a debt open, or opens one and schedules its timetable on
 * Collections' own queues (claimed rows with a due time), counted from that first decline. The retries (day 3 and day 7)
 * ask Invoicing to request payment again for each unpaid invoice of the case; the steps that change the state are
 * {@link CaseTimers}. The case closes when none of its invoices is unpaid, and the standing is GOOD again.
 */
@Service
@EnableConfigurationProperties(CollectionsTimetable.class)
class CollectionsService implements Collections, QueueHandler {

    private static final Logger log = LoggerFactory.getLogger(CollectionsService.class);

    /** The payload's mark of a "pay now" request, which brings its number. */
    static final String REQUEST = "REQUEST";

    /** A decline finds its case closed while it waits only if the last invoice is paid at that moment. */
    static final int JOIN_ATTEMPTS = 3;

    // A money queue (FR-037): a retry collects money. The permanent guard is one case_retry row per case and
    // retry number; Payment's own retry number makes a repeated call harmless as well.
    static final QueueDefinition DEFINITION = QueueDefinition.of("collections-retry", "collections.retry_queue",
            "collections.case_retry_one_per_case_and_number").asMoneyQueue();

    private final CollectionCases cases;
    private final InvoicingApi invoicing;
    private final CaseSettlement settlement;
    private final CaseTimers timers;
    private final CollectionsTimetable timetable;
    private final DurableQueue queue;
    private final TransactionTemplate transactions;
    private final Clock clock;

    CollectionsService(CollectionCases cases, InvoicingApi invoicing, CaseSettlement settlement, CaseTimers timers,
            CollectionsTimetable timetable, DurableQueues queues, TransactionTemplate transactions, Clock clock) {
        this.cases = cases;
        this.invoicing = invoicing;
        this.settlement = settlement;
        this.timers = timers;
        this.timetable = timetable;
        this.transactions = transactions;
        this.clock = clock;
        this.queue = queues.register(DEFINITION, this);
    }

    /**
     * The invoice joins the account's case with a debt open (OPEN, OVERDUE, BLOCKED; WRITTEN_OFF too, where only a
     * repeat can arrive), or opens one. The case opens with an atomic insert, and only the call that opens it schedules
     * its timetable, in the same transaction. A repeated decline adds nothing. A decline of an invoice Invoicing says is
     * paid (a publication resubmitted late) adds nothing: the case covers unpaid invoices only, and only
     * {@code InvoicePaid} moves it back. A join changes neither the case's state nor the standing.
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
                schedule(caseId, declined.occurredAt());
                return;
            }
            Optional<CollectionCase> withDebt = cases.lockCaseWithDebt(declined.accountId());
            if (withDebt.isPresent()) {
                cases.addInvoice(withDebt.get().id(), declined.invoiceId(), now);
                return;
            }
            // The case closed while this call waited for its lock: open a new one.
        }
        throw new IllegalStateException("Account " + declined.accountId() + ": no case to join after "
                + JOIN_ATTEMPTS + " attempts");
    }

    private void schedule(UUID caseId, Instant firstDecline) {
        queue.enqueue(caseId + ":" + RetryStep.DAY_3, firstDecline.plus(timetable.firstRetry()),
                caseId + "," + RetryStep.DAY_3);
        queue.enqueue(caseId + ":" + RetryStep.DAY_7, firstDecline.plus(timetable.secondRetry()),
                caseId + "," + RetryStep.DAY_7);
        timers.schedule(caseId, firstDecline);
    }

    private boolean paid(UUID accountId, UUID invoiceId) {
        return invoicing.invoicesFor(accountId).stream()
                .anyMatch(i -> i.id().equals(invoiceId) && i.status() == InvoiceStatus.PAID);
    }

    /**
     * One of the case's invoices is paid, in any state with a debt open: the case closes once none of its invoices is
     * unpaid, and the standing is GOOD in the same transaction (rule 5). After a write-off the subscription stays
     * ended. Under the case's lock, so a decline that joins and a timer at the same time read the state this leaves.
     */
    @ApplicationModuleListener
    void on(InvoicePaid paid) {
        cases.lockCaseWithDebt(paid.accountId()).ifPresent(locked -> settlement.paid(locked, paid.invoiceId()));
    }

    /**
     * A retry of the case: a step of the timetable (day 3, day 7) or a "pay now" request. Called with no transaction
     * open (FR-005). Under the case's row lock it takes the retry's number: a "pay now" request brings its own; a step
     * takes the case's next number, or the one it stored on an earlier run (#80, option B: one sequence for each
     * case). Then, after the commit, it asks Invoicing again for each unpaid invoice of the case, which asks Payment,
     * which may ask the provider. Run again after a crash, it asks again with the same number; Payment makes each
     * retry of each invoice once. A retry changes no state: the timers decide.
     *
     * <p>Payloads: {@code <case>,DAY_3}, {@code <case>,DAY_7}, {@code <case>,REQUEST,<number>}; and, queued by
     * earlier releases, {@code <case>} (day 3, Slice 1R) and {@code <case>,2} (day 7, Slice 3 PR-4).
     */
    @Override
    public Outcome handle(QueueItem item) {
        String[] payload = item.payload().split(",");
        UUID caseId = UUID.fromString(payload[0]);
        record Asked(int retry, List<UUID> unpaid) {
        }
        Asked asked = transactions.execute(tx -> cases.lock(caseId).filter(CollectionCase::hasDebt).map(locked -> {
            int retry = payload.length == 3 && REQUEST.equals(payload[1]) ? Integer.parseInt(payload[2])
                    : cases.retryNumberOf(caseId, step(payload), clock.instant());
            return new Asked(retry, cases.unpaidOf(caseId));
        }).orElse(null));
        if (asked != null) {
            for (UUID invoiceId : asked.unpaid()) {
                invoicing.requestPaymentAgain(invoiceId, asked.retry());
            }
        }
        return Outcome.done();
    }

    private static RetryStep step(String[] payload) {
        if (payload.length == 1 || "1".equals(payload[1])) {
            return RetryStep.DAY_3;
        }
        return "2".equals(payload[1]) ? RetryStep.DAY_7 : RetryStep.valueOf(payload[1]);
    }

    /**
     * Under the case's row lock, so two requests at the same time get two numbers. Every request of a case, scheduled
     * or "pay now", takes the next retry number at the moment it is asked (#80, option B), so an early "pay now"
     * changes nothing in the timetable. The retry's row is recorded here, as its permanent guard (rule 11), and the
     * retry is queued, due now.
     */
    @Override
    @Transactional
    public Optional<PaymentRequestView> requestPayment(UUID accountId) {
        return cases.lockCaseWithDebt(accountId).map(locked -> {
            int retry = cases.lastRetryOf(locked.id()) + 1;
            cases.recordRetry(locked.id(), retry, clock.instant());
            queue.enqueue(locked.id() + ":" + REQUEST + ":" + retry, clock.instant(),
                    locked.id() + "," + REQUEST + "," + retry);
            return new PaymentRequestView(locked.id(), retry, cases.unpaidOf(locked.id()));
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CaseView> caseFor(UUID accountId) {
        return cases.findByAccountIdOrderByOpenedAtDesc(accountId).stream()
                .sorted(Comparator.comparing((CollectionCase c) -> c.status() == CaseStatus.CLOSED))
                .findFirst()
                .map(c -> new CaseView(c.id(), c.accountId(), c.invoiceId(), c.status(), c.openedAt(),
                        c.closedAt(), cases.retriesOf(c.id()), cases.invoicesOf(c.id()), cases.unpaidOf(c.id())));
    }
}
