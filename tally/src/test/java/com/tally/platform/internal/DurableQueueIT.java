package com.tally.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tally.AbstractIntegrationTest;
import com.tally.platform.DurableQueue;
import com.tally.platform.Outcome;
import com.tally.platform.QueueDefinition;
import com.tally.platform.QueueHandler;
import com.tally.platform.QueueItem;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** ADR-017: per-module queue tables, claimed with FOR UPDATE SKIP LOCKED, with a reaper. */
class DurableQueueIT extends AbstractIntegrationTest {

    static final QueueDefinition WORK = QueueDefinition.of("test-work", "platform_test.work_queue");

    @Autowired
    JdbcDurableQueues queues;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    final List<QueueItem> handled = new CopyOnWriteArrayList<>();

    @BeforeEach
    void emptyQueue() {
        jdbc.update("DELETE FROM platform_test.work_queue");
        handled.clear();
    }

    JdbcDurableQueue queue(QueueHandler handler) {
        return queues.create(WORK, handler, "test-instance");
    }

    QueueHandler recording(Outcome outcome) {
        return item -> {
            handled.add(item);
            return outcome;
        };
    }

    String status(String key) {
        return jdbc.queryForObject("SELECT status FROM platform_test.work_queue WHERE queue_key = ?", String.class, key);
    }

    @Test
    void enqueueIsIdempotentPerKey() {
        DurableQueue queue = queue(recording(Outcome.done()));

        assertThat(queue.enqueue("account-1:2026-02-15", clock.instant(), "first")).isTrue();
        assertThat(queue.enqueue("account-1:2026-02-15", clock.instant(), "second")).isFalse();

        assertThat(jdbc.queryForObject("SELECT payload FROM platform_test.work_queue", String.class))
                .isEqualTo("first");
    }

    @Test
    void itemsAreHandledOnlyOnceDue() {
        DurableQueue queue = queue(recording(Outcome.done()));
        queue.enqueue("later", clock.instant().plus(Duration.ofDays(31)), "p");

        assertThat(queue.processDue()).isZero();

        clock.advance(Duration.ofDays(31));
        assertThat(queue.processDue()).isEqualTo(1);
        assertThat(handled).extracting(QueueItem::key).containsExactly("later");
        assertThat(handled.getFirst().attempts()).isEqualTo(1);
        assertThat(status("later")).isEqualTo("DONE");
        assertThat(queue.processDue()).isZero();
    }

    @Test
    void aThrowingHandlerMarksTheItemFailed() {
        DurableQueue queue = queue(item -> {
            throw new IllegalStateException("provider exploded");
        });
        queue.enqueue("k", clock.instant(), "p");

        queue.processDue();

        assertThat(status("k")).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_error FROM platform_test.work_queue", String.class))
                .contains("provider exploded");
        assertThat(queue.processDue()).isZero();
    }

    @Test
    void retryAtReturnsTheItemWithANewDueTime() {
        Instant retry = clock.instant().plusSeconds(30);
        DurableQueue queue = queue(recording(Outcome.retryAt(retry)));
        queue.enqueue("k", clock.instant(), "p");

        queue.processDue();

        assertThat(status("k")).isEqualTo("READY");
        assertThat(queue.processDue()).isZero();
        clock.advance(Duration.ofSeconds(30));
        assertThat(queue.processDue()).isEqualTo(1);
        assertThat(handled).extracting(QueueItem::attempts).containsExactly(1, 2);
    }

    @Test
    void twoInstancesClaimingConcurrentlyHandleEachItemExactlyOnce() throws Exception {
        int items = 1000;
        Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
        QueueHandler counting = item -> {
            counts.computeIfAbsent(item.key(), k -> new AtomicInteger()).incrementAndGet();
            return Outcome.done();
        };
        JdbcDurableQueue a = queues.create(WORK, counting, "instance-a");
        JdbcDurableQueue b = queues.create(WORK, counting, "instance-b");
        for (int i = 0; i < items; i++) {
            a.enqueue("item-" + i, clock.instant(), "p");
        }

        // Four threads per instance, all claiming at once.
        List<Thread> threads = new ArrayList<>();
        for (JdbcDurableQueue q : List.of(a, b)) {
            for (int t = 0; t < 4; t++) {
                threads.add(Thread.ofPlatform().start(() -> {
                    while (q.processDue() > 0) {
                        // keep claiming until nothing is due
                    }
                }));
            }
        }
        for (Thread t : threads) {
            t.join();
        }

        assertThat(counts).hasSize(items);
        assertThat(counts.values()).allSatisfy(c -> assertThat(c.get()).isEqualTo(1));
        assertThat(jdbc.queryForObject(
                "SELECT count(DISTINCT claimed_by) FROM platform_test.work_queue", Integer.class)).isEqualTo(2);
    }

    @Test
    void theReaperReturnsAbandonedClaimsAndALateCompletionIsIgnored() {
        JdbcDurableQueue crashed = queue(recording(Outcome.done()));
        crashed.enqueue("k", clock.instant(), "p");
        JdbcDurableQueue.Claim abandoned = crashed.claim(10).getFirst();

        // Still within the claim timeout (5 minutes): nothing to reap.
        clock.advance(Duration.ofMinutes(4));
        assertThat(crashed.reap()).isZero();
        assertThat(status("k")).isEqualTo("IN_PROCESSING");

        clock.advance(Duration.ofMinutes(2));
        assertThat(crashed.reap()).isEqualTo(1);
        assertThat(status("k")).isEqualTo("READY");

        // Another instance takes the item. The first instance then wakes up and tries to finish it.
        JdbcDurableQueue survivor = queues.create(WORK, recording(Outcome.retryAt(clock.instant().plusSeconds(60))), "b");
        assertThat(survivor.processDue()).isEqualTo(1);
        assertThat(crashed.complete(abandoned, Outcome.done())).isFalse();
        assertThat(status("k")).isEqualTo("READY");
        assertThat(handled.getFirst().attempts()).isEqualTo(2);
    }

    @Test
    void aClosedGateClaimsNothingAndTheItemsWait() {
        AtomicBoolean open = new AtomicBoolean(false);
        JdbcDurableQueue queue = queues.create(WORK, JdbcDurableQueue.processorFor(recording(Outcome.done())),
                open::get, "test-instance");
        queue.enqueue("k", clock.instant(), "p");

        assertThat(queue.processDue()).isZero();
        assertThat(status("k")).isEqualTo("READY");
        assertThat(queue.waiting()).isEqualTo(1);

        open.set(true);
        assertThat(queue.processDue()).isEqualTo(1);
    }

    @Test
    void claimingInsideATransactionIsRefused() {
        DurableQueue queue = queue(recording(Outcome.done()));
        queue.enqueue("k", clock.instant(), "p");

        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> queue.processDue()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADR-005");
        assertThat(status("k")).isEqualTo("READY");
    }

    @Test
    void registrationRejectsATableWithoutTheQueueShape() {
        assertThatThrownBy(() -> queues.register(
                QueueDefinition.of("not-a-queue", "platform_test.not_a_queue"), recording(Outcome.done())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lacks columns");
        assertThatThrownBy(() -> queues.register(
                QueueDefinition.of("missing", "platform_test.missing"), recording(Outcome.done())))
                .hasMessageContaining("does not exist");
    }
}
