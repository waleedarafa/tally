package com.tally.platform.internal;

import com.tally.platform.ClaimGate;
import com.tally.platform.DurableQueue;
import com.tally.platform.Outcome;
import com.tally.platform.QueueDefinition;
import com.tally.platform.QueueHandler;
import com.tally.platform.QueueItem;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A durable queue on one table (FR-017). Every statement runs on its own and commits at once:
 * the claim is committed before a handler runs, so no transaction is open while it works (FR-005).
 * All times come from the injected clock, never from the database (D4).
 */
final class JdbcDurableQueue implements DurableQueue {

    private static final Logger log = LoggerFactory.getLogger(JdbcDurableQueue.class);

    /** A claimed item and the token that proves this claim still owns it. */
    record Claim(QueueItem item, UUID token) {
    }

    /** Does the work for a claim and records its result. Must not throw. */
    interface Processor {
        void process(JdbcDurableQueue queue, Claim claim);
    }

    private final QueueDefinition definition;
    private final Processor processor;
    private final ClaimGate gate;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String instance;
    private final Duration claimTimeout;
    private final String table;

    JdbcDurableQueue(QueueDefinition definition, Processor processor, ClaimGate gate, JdbcTemplate jdbc, Clock clock,
            String instance, Duration claimTimeout) {
        this.definition = definition;
        this.processor = processor;
        this.gate = gate;
        this.jdbc = jdbc;
        this.clock = clock;
        this.instance = instance;
        this.claimTimeout = claimTimeout;
        this.table = definition.table();
    }

    QueueDefinition definition() {
        return definition;
    }

    Instant now() {
        return clock.instant();
    }

    @Override
    public String name() {
        return definition.name();
    }

    @Override
    public boolean enqueue(String key, Instant dueAt, String payload) {
        int inserted = jdbc.update("INSERT INTO " + table
                        + " (queue_key, due_at, payload, status, attempts, created_at)"
                        + " VALUES (?, ?, ?, 'READY', 0, ?) ON CONFLICT (queue_key) DO NOTHING",
                key, at(dueAt), payload, at(clock.instant()));
        return inserted == 1;
    }

    @Override
    public int processDue() {
        List<Claim> claims = claim(definition.batchSize());
        claims.forEach(this::handle);
        return claims.size();
    }

    /**
     * Claims up to {@code max} due items with FOR UPDATE SKIP LOCKED, so instances never share an item.
     * Claims nothing while the queue's gate is closed.
     */
    List<Claim> claim(int max) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Queue " + name() + " claimed inside a transaction; claims must commit before work starts (FR-005)");
        }
        if (!gate.open()) {
            return List.of();
        }
        UUID token = UUID.randomUUID();
        Instant now = clock.instant();
        return jdbc.query("UPDATE " + table
                        + " SET status = 'IN_PROCESSING', claim_token = ?, claimed_at = ?, claimed_by = ?,"
                        + " attempts = attempts + 1"
                        + " WHERE id IN (SELECT id FROM " + table
                        + " WHERE status = 'READY' AND due_at <= ? ORDER BY due_at, id LIMIT ? FOR UPDATE SKIP LOCKED)"
                        + " RETURNING id, queue_key, due_at, payload, attempts",
                (rs, n) -> new Claim(new QueueItem(
                        rs.getLong("id"),
                        rs.getString("queue_key"),
                        rs.getObject("due_at", OffsetDateTime.class).toInstant(),
                        rs.getString("payload"),
                        rs.getInt("attempts")), token),
                token, at(now), instance, at(now), max);
    }

    /** Does the work for one claimed item and records its result. */
    void handle(Claim claim) {
        processor.process(this, claim);
    }

    /** The processor for a {@link QueueHandler}: its outcome is recorded; an exception marks the item FAILED. */
    static Processor processorFor(QueueHandler handler) {
        return (queue, claim) -> {
            Outcome outcome;
            try {
                outcome = handler.handle(claim.item());
            } catch (Exception e) {
                log.warn("Queue {} item {} failed", queue.name(), claim.item().key(), e);
                queue.fail(claim, e);
                return;
            }
            queue.complete(claim, outcome);
        };
    }

    /** Records an outcome, if this claim still owns the item. Returns whether it did. */
    boolean complete(Claim claim, Outcome outcome) {
        int updated = switch (outcome) {
            case Outcome.Done done -> jdbc.update("UPDATE " + table
                            + " SET status = 'DONE', claim_token = NULL, last_error = NULL"
                            + " WHERE id = ? AND claim_token = ?",
                    claim.item().id(), claim.token());
            case Outcome.RetryAt retry -> jdbc.update("UPDATE " + table
                            + " SET status = 'READY', due_at = ?, claim_token = NULL, last_error = NULL"
                            + " WHERE id = ? AND claim_token = ?",
                    at(retry.dueAt()), claim.item().id(), claim.token());
        };
        return owned(claim, updated);
    }

    boolean fail(Claim claim, Exception error) {
        int updated = jdbc.update("UPDATE " + table
                        + " SET status = 'FAILED', claim_token = NULL, last_error = ?"
                        + " WHERE id = ? AND claim_token = ?",
                String.valueOf(error), claim.item().id(), claim.token());
        return owned(claim, updated);
    }

    /** Periodic jobs: back to READY at {@code next}. A success also records {@code last_success_at}. */
    boolean reschedule(Claim claim, Instant next, Optional<Exception> error) {
        int updated = jdbc.update("UPDATE " + table
                        + " SET status = 'READY', due_at = ?, claim_token = NULL, last_error = ?,"
                        + " last_success_at = CASE WHEN ? THEN ? ELSE last_success_at END"
                        + " WHERE id = ? AND claim_token = ?",
                at(next), error.map(String::valueOf).orElse(null),
                error.isEmpty(), at(clock.instant()), claim.item().id(), claim.token());
        return owned(claim, updated);
    }

    /** Returns items IN_PROCESSING for longer than the claim timeout to READY. Returns how many. */
    int reap() {
        int reaped = jdbc.update("UPDATE " + table
                        + " SET status = 'READY', claim_token = NULL, claimed_by = NULL"
                        + " WHERE status = 'IN_PROCESSING' AND claimed_at < ?",
                at(clock.instant().minus(claimTimeout)));
        if (reaped > 0) {
            log.warn("Queue {}: reaper returned {} stale claims", name(), reaped);
        }
        return reaped;
    }

    /** Due items not yet finished: READY or IN_PROCESSING with {@code due_at <= now}. */
    long waiting() {
        return jdbc.queryForObject("SELECT count(*) FROM " + table
                + " WHERE status IN ('READY', 'IN_PROCESSING') AND due_at <= ?", Long.class, at(clock.instant()));
    }

    /** Seconds since the oldest unfinished item fell due; zero when nothing is due. */
    double oldestDueAgeSeconds() {
        Instant now = clock.instant();
        OffsetDateTime oldest = jdbc.queryForObject("SELECT min(due_at) FROM " + table
                + " WHERE status IN ('READY', 'IN_PROCESSING') AND due_at <= ?", OffsetDateTime.class, at(now));
        return oldest == null ? 0 : Duration.between(oldest.toInstant(), now).toMillis() / 1000.0;
    }

    /** When the item with this key last succeeded, and when it was created. */
    Optional<Instant> lastSuccess(String key) {
        List<OffsetDateTime> found = jdbc.queryForList(
                "SELECT last_success_at FROM " + table + " WHERE queue_key = ?", OffsetDateTime.class, key);
        return found.stream().filter(Objects::nonNull).findFirst().map(OffsetDateTime::toInstant);
    }

    Optional<Instant> createdAt(String key) {
        return jdbc.queryForList("SELECT created_at FROM " + table + " WHERE queue_key = ?", OffsetDateTime.class, key)
                .stream().findFirst().map(OffsetDateTime::toInstant);
    }

    long failed() {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE status = 'FAILED'", Long.class);
    }

    private boolean owned(Claim claim, int updated) {
        if (updated == 0) {
            log.warn("Queue {} item {}: claim no longer owned (reaped and reclaimed); outcome ignored",
                    name(), claim.item().key());
        }
        return updated == 1;
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
