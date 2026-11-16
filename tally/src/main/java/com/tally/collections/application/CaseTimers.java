package com.tally.collections.application;

import com.tally.collections.domain.CaseStep;
import com.tally.collections.domain.CollectionCase;
import com.tally.collections.domain.CollectionCases;
import com.tally.platform.DurableQueue;
import com.tally.platform.DurableQueues;
import com.tally.platform.Outcome;
import com.tally.platform.QueueDefinition;
import com.tally.platform.QueueHandler;
import com.tally.platform.QueueItem;
import com.tally.subscription.EndReason;
import com.tally.subscription.SubscriptionApi;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The steps of a case's timetable that change its state (brief rules 1, 2, 5 and 6; design.md §5): OVERDUE one hour
 * after retry 2 is asked, BLOCKED on day 10, WRITTEN_OFF on day 30, each only if a debt is still open. Each is a
 * claimed row on Collections' timer queue, guarded by {@code case_step_one_per_case_and_step}.
 *
 * <p>A step runs in one transaction, under the case's row lock: first it asks Invoicing which invoices are still
 * unpaid, and closes the case if none is; else it moves the case forward by its rule, from whatever state it finds
 * (a late timer included), and sets the standing in the same transaction. The write-off ends the subscription.
 */
@Component
class CaseTimers implements QueueHandler {

    private static final Logger log = LoggerFactory.getLogger(CaseTimers.class);

    // Not a money queue: a step moves no money. The permanent guard is one case_step row per case and step.
    static final QueueDefinition DEFINITION = QueueDefinition.of("collections-timers", "collections.timer_queue",
            "collections.case_step_one_per_case_and_step");

    private final CollectionCases cases;
    private final CaseSettlement settlement;
    private final SubscriptionApi subscriptions;
    private final CollectionsTimetable timetable;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final DurableQueue queue;

    CaseTimers(CollectionCases cases, CaseSettlement settlement, SubscriptionApi subscriptions,
            CollectionsTimetable timetable, DurableQueues queues, TransactionTemplate transactions, Clock clock) {
        this.cases = cases;
        this.settlement = settlement;
        this.subscriptions = subscriptions;
        this.timetable = timetable;
        this.transactions = transactions;
        this.clock = clock;
        this.queue = queues.register(DEFINITION, this);
    }

    /** Schedules the case's steps, counted from its first decline, in the caller's transaction. */
    void schedule(UUID caseId, Instant firstDecline) {
        enqueue(caseId, CaseStep.OVERDUE, firstDecline.plus(timetable.overdue()));
        enqueue(caseId, CaseStep.BLOCKED, firstDecline.plus(timetable.blocked()));
        enqueue(caseId, CaseStep.WRITTEN_OFF, firstDecline.plus(timetable.writtenOff()));
    }

    private void enqueue(UUID caseId, CaseStep step, Instant due) {
        queue.enqueue(caseId + ":" + step, due, caseId + "," + step);
    }

    @Override
    public Outcome handle(QueueItem item) {
        String[] payload = item.payload().split(",");
        UUID caseId = UUID.fromString(payload[0]);
        CaseStep step = CaseStep.valueOf(payload[1]);
        transactions.executeWithoutResult(tx -> take(caseId, step));
        return Outcome.done();
    }

    private void take(UUID caseId, CaseStep step) {
        Optional<CollectionCase> found = cases.lock(caseId);
        if (found.isEmpty() || !found.get().hasDebt()) {
            return;
        }
        CollectionCase locked = found.get();
        if (!cases.recordStep(caseId, step, clock.instant())) {
            log.info("Case {} took step {} already", caseId, step);
            return;
        }
        if (settlement.settleWithInvoicing(locked)) {
            log.info("Case {}: none of its invoices is unpaid; closed on step {}", caseId, step);
            return;
        }
        if (!locked.take(step)) {
            log.info("Case {} is {}: step {} changes nothing", caseId, locked.status(), step);
            return;
        }
        settlement.follow(locked);
        if (step == CaseStep.WRITTEN_OFF) {
            subscriptions.endLiveSubscriptions(locked.accountId(), EndReason.WRITE_OFF);
        }
    }
}
