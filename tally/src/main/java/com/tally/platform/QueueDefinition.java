package com.tally.platform;

import java.util.regex.Pattern;

/**
 * A durable queue (ADR-017): one table in the owning module's schema, polled by every instance.
 *
 * <p>Every queue table has this shape. The unique constraint on {@code queue_key} is the
 * business unique constraint: the final guard against duplicate work.
 * <pre>{@code
 * CREATE TABLE <schema>.<table> (
 *     id              BIGSERIAL    PRIMARY KEY,
 *     queue_key       TEXT         NOT NULL UNIQUE,
 *     due_at          TIMESTAMPTZ  NOT NULL,
 *     payload         TEXT         NOT NULL,
 *     status          TEXT         NOT NULL CHECK (status IN ('READY', 'IN_PROCESSING', 'DONE', 'FAILED')),
 *     attempts        INT          NOT NULL DEFAULT 0,
 *     claim_token     UUID,
 *     claimed_at      TIMESTAMPTZ,
 *     claimed_by      TEXT,
 *     last_error      TEXT,
 *     last_success_at TIMESTAMPTZ,
 *     created_at      TIMESTAMPTZ  NOT NULL
 * );
 * CREATE INDEX ON <schema>.<table> (due_at) WHERE status IN ('READY', 'IN_PROCESSING');
 * }</pre>
 * The platform checks the table's columns and key constraint when the queue is registered.
 *
 * @param name      the queue's name, used as the {@code queue} tag of its health metrics
 * @param table     schema-qualified table name, for example {@code invoicing.billing_schedule}
 * @param workers   worker threads on each instance (a bulkhead per queue)
 * @param batchSize the most rows one claim takes
 */
public record QueueDefinition(String name, String table, int workers, int batchSize) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");
    private static final Pattern TABLE = Pattern.compile("[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*");

    public QueueDefinition {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Queue name must match " + NAME + ": " + name);
        }
        // Table names are written into SQL, so only plain identifiers are accepted.
        if (!TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("Table must be schema.table in lower case: " + table);
        }
        if (workers < 1 || batchSize < 1) {
            throw new IllegalArgumentException("workers and batchSize must be positive");
        }
    }

    public static QueueDefinition of(String name, String table) {
        return new QueueDefinition(name, table, 1, 10);
    }

    public QueueDefinition withWorkers(int workers) {
        return new QueueDefinition(name, table, workers, batchSize);
    }

    public QueueDefinition withBatchSize(int batchSize) {
        return new QueueDefinition(name, table, workers, batchSize);
    }
}
