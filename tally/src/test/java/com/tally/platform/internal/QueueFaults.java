package com.tally.platform.internal;

import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Queue faults for tests outside the platform, which may not write queue rows themselves (F13, ADR-029). The
 * platform owns the queue tables, so the fault is made here, by queue name, through the queue's own definition.
 */
public final class QueueFaults {

    private final JdbcDurableQueues queues;
    private final JdbcTemplate jdbc;

    private QueueFaults(JdbcDurableQueues queues, JdbcTemplate jdbc) {
        this.queues = queues;
        this.jdbc = jdbc;
    }

    /** The faults of the queues registered in {@code context}. */
    public static QueueFaults of(ApplicationContext context) {
        return new QueueFaults(context.getBean(JdbcDurableQueues.class), context.getBean(JdbcTemplate.class));
    }

    /**
     * Simulate an instance that crashed after handling the item and before marking it done (D1): the item, already
     * DONE, is READY again, so a poller delivers it a second time.
     */
    public void redeliver(String queueName, String key) {
        String table = queues.queues().stream()
                .filter(q -> q.name().equals(queueName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No queue named " + queueName))
                .definition().table();
        int updated = jdbc.update("UPDATE " + table + " SET status = 'READY', claim_token = NULL WHERE queue_key = ?"
                + " AND status = 'DONE'", key);
        if (updated != 1) {
            throw new IllegalStateException("No DONE item " + key + " in queue " + queueName + " to redeliver");
        }
    }
}
