package com.tally.platform.internal;

import com.tally.platform.IdempotencyStore;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link IdempotencyStore} in one module's table. The claim is an atomic insert on the primary key
 * (scope, key); an existing row is read under a row lock, so a stale key, or one whose request died, is taken
 * over by one request only. Built per table by {@link JdbcIdempotencyKeys}, not a Spring bean, so its
 * transactions are explicit: the claim and the release run in their own; the response joins the caller's.
 */
class JdbcIdempotencyStore implements IdempotencyStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate own;
    private final String table;
    private final PlatformProperties.Idempotency settings;
    private final Clock clock;

    JdbcIdempotencyStore(JdbcTemplate jdbc, PlatformTransactionManager transactions, String table,
            PlatformProperties.Idempotency settings, Clock clock) {
        this.jdbc = jdbc;
        this.own = new TransactionTemplate(transactions);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.table = table;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public Claim claim(String scope, String key, String requestHash) {
        return own.execute(tx -> claimInTransaction(scope, key, requestHash));
    }

    private Claim claimInTransaction(String scope, String key, String requestHash) {
        Instant now = clock.instant();
        Timestamp at = Timestamp.from(now);
        if (jdbc.update("INSERT INTO " + table + " (scope, key, request_hash, created_at, claimed_at)"
                + " VALUES (?, ?, ?, ?, ?) ON CONFLICT (scope, key) DO NOTHING", scope, key, requestHash, at, at) == 1) {
            return new Act();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT request_hash, response_status,"
                + " response_content_type, response_body, created_at, claimed_at FROM " + table
                + " WHERE scope = ? AND key = ? FOR UPDATE", scope, key);
        if (rows.isEmpty()) {
            // Released between the insert and the read: the client may simply try again.
            return new InProgress(Duration.ofSeconds(1));
        }
        Map<String, Object> row = rows.getFirst();
        if (!instant(row, "created_at").plus(settings.keptFor()).isAfter(now)) {
            jdbc.update("UPDATE " + table + " SET request_hash = ?, created_at = ?, claimed_at = ?,"
                    + " response_status = NULL, response_content_type = NULL, response_body = NULL"
                    + " WHERE scope = ? AND key = ?", requestHash, at, at, scope, key);
            return new Act();
        }
        if (!requestHash.equals(row.get("request_hash"))) {
            return new Reused();
        }
        if (row.get("response_status") != null) {
            return new Replay(new StoredResponse((Integer) row.get("response_status"),
                    (String) row.get("response_content_type"), (String) row.get("response_body")));
        }
        Instant timesOut = instant(row, "claimed_at").plus(settings.requestTimeout());
        if (timesOut.isAfter(now)) {
            return new InProgress(Duration.between(now, timesOut));
        }
        // The request holding the key started longer ago than the timeout: it died. Its business change, if
        // any, rolled back with its response, so this request acts again.
        jdbc.update("UPDATE " + table + " SET claimed_at = ? WHERE scope = ? AND key = ?", at, scope, key);
        return new Act();
    }

    @Override
    public void complete(String scope, String key, StoredResponse response) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A response is stored in the transaction of the request's business change");
        }
        jdbc.update("UPDATE " + table + " SET response_status = ?, response_content_type = ?, response_body = ?"
                + " WHERE scope = ? AND key = ?", response.status(), response.contentType(), response.body(), scope,
                key);
    }

    @Override
    public void release(String scope, String key) {
        own.executeWithoutResult(tx -> jdbc.update("DELETE FROM " + table
                + " WHERE scope = ? AND key = ? AND response_status IS NULL", scope, key));
    }

    private static Instant instant(Map<String, Object> row, String column) {
        return ((Timestamp) row.get(column)).toInstant();
    }
}
