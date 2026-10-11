package com.tally.platform;

/**
 * Registers idempotency-key stores (ADR-019, CLAUDE.md rule 13): one table per module, in the module's own
 * schema, like the durable queues. Every table has this shape; the platform checks it at registration:
 * <pre>{@code
 * CREATE TABLE <schema>.idempotency_key (
 *     scope                 TEXT        NOT NULL,
 *     key                   TEXT        NOT NULL,
 *     request_hash          TEXT        NOT NULL,
 *     response_status       INT,
 *     response_content_type TEXT,
 *     response_body         TEXT,
 *     created_at            TIMESTAMPTZ NOT NULL,
 *     claimed_at            TIMESTAMPTZ NOT NULL,
 *     PRIMARY KEY (scope, key)
 * );
 * }</pre>
 * {@code scope} is who the key belongs to: the account until callers are authenticated, then the caller.
 */
public interface IdempotencyKeys {

    /** Registers the store in {@code table} (schema-qualified). Fails fast if the table lacks the shape above. */
    IdempotencyStore register(String table);
}
