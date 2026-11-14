package com.tally.accessfeed.application;

import com.tally.accessfeed.application.AccessFactsLog.LogUnavailable;
import com.tally.accessfeed.domain.AccountFacts;
import com.tally.accessfeed.domain.AccountFactsStore;
import com.tally.platform.DurableQueue;
import com.tally.platform.DurableQueues;
import com.tally.platform.Outcome;
import com.tally.platform.QueueDefinition;
import com.tally.platform.QueueHandler;
import com.tally.platform.QueueItem;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends each account's current facts to the event log (ADR-033 decision 2, CLAUDE.md rule 15), on the platform
 * queue (rule 5). Every change of an account's facts enqueues one item; the item sends what is current then.
 *
 * <p>The property: <b>for one account, only one send runs at a time, and each send carries the facts current when it
 * starts</b>, so a later send never carries an older sequence. A send holds the account's row lock, the same lock
 * every change of the facts takes (CLAUDE.md rule 7): two sends for one account, on any instance, run one after the
 * other, and no change commits while one runs.
 *
 * <p>How long the lock is held, at most: a new producer's first connection ({@code max.block.ms}, the send timeout),
 * then the wait for the acknowledgement (send timeout + 1 s), then the commit (up to {@code max.block.ms} again).
 * With the defaults (5 s) that is about 16 s. The transaction timeout (15 s) is still enough: the broker's timer
 * starts with the transaction's first send, after the connection, so it covers the acknowledgement and the commit
 * (at most 11 s). CLAUDE.md rule 15 allows this one database transaction open during a call out (rule 6): one
 * connection for each relay worker, and the workers stay few.
 *
 * <p>The sequence is recorded as sent only after the log has acknowledged it, in the same transaction. If the instance
 * dies between the two, the item comes back and the same sequence is sent again: Access ignores a sequence it has.
 * A send whose facts are already in the log (a later item sent them) sends nothing.
 */
@Service
class AccessFactsRelay implements QueueHandler {

    private static final Logger log = LoggerFactory.getLogger(AccessFactsRelay.class);

    // Not a money queue. The permanent guard is the one row of facts per account and its sent_sequence, which only
    // rises: a sequence the log has is never sent again, except after a crash between the send and its record.
    static final QueueDefinition DEFINITION = QueueDefinition.of("access-feed-relay", "accessfeed.relay_queue",
            "accessfeed.account_facts_pkey");

    private final AccountFactsStore facts;
    private final AccessFactsLog eventLog;
    private final TransactionTemplate transactions;
    private final AccessFeedProperties properties;
    private final Clock clock;
    private final DurableQueue queue;

    AccessFactsRelay(AccountFactsStore facts, AccessFactsLog eventLog, TransactionTemplate transactions,
            AccessFeedProperties properties, DurableQueues queues, Clock clock) {
        this.facts = facts;
        this.eventLog = eventLog;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
        this.queue = queues.register(DEFINITION.withWorkers(properties.relay().workers()), this);
    }

    /** Called in the transaction that changed the facts to {@code sequence}: the item commits with the change. */
    void changed(UUID accountId, long sequence) {
        queue.enqueue(accountId + ":" + sequence, clock.instant(), accountId.toString());
    }

    @Override
    public Outcome handle(QueueItem item) {
        UUID accountId = UUID.fromString(item.payload());
        try {
            transactions.executeWithoutResult(tx -> send(accountId));
            return Outcome.done();
        } catch (UnacknowledgedSend unacknowledged) {
            log.warn("The log did not acknowledge the facts of account {}; trying again in {}", accountId,
                    properties.relay().retryAfter(), unacknowledged.getCause());
            return Outcome.retryAt(clock.instant().plus(properties.relay().retryAfter()));
        }
    }

    /** Under the account's lock: sends the current facts unless the log already has them, then records it. */
    private void send(UUID accountId) {
        AccountFacts current = facts.lockOrCreate(accountId);
        if (current.sequence() <= facts.sentSequence(accountId)) {
            return;
        }
        try {
            eventLog.send(accountId, AccessFactsMessages.json(current));
        } catch (LogUnavailable e) {
            throw new UnacknowledgedSend(e);
        }
        facts.recordSent(accountId, current.sequence());
    }

    /** Rolls the transaction back; the item is tried again. */
    private static final class UnacknowledgedSend extends RuntimeException {

        UnacknowledgedSend(LogUnavailable cause) {
            super(cause);
        }
    }
}
