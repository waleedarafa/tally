package com.tally.subscription.adapter.persistence;

import com.tally.subscription.domain.IdempotentRequests;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link IdempotentRequests} in {@code subscription.idempotency_key}. The claim is an atomic insert on
 * (account, key), the primary key {@code idempotency_key_pkey}; an existing row is read under a row lock, so
 * a stale key, or one whose request died, is taken over by one request only.
 */
@Repository
class JdbcIdempotentRequests implements IdempotentRequests {

    private final JdbcTemplate jdbc;

    JdbcIdempotentRequests(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim claim(UUID accountId, String key, String requestHash, Instant now, Duration keptFor,
            Duration requestTimeout) {
        Timestamp at = Timestamp.from(now);
        if (jdbc.update("INSERT INTO subscription.idempotency_key (account_id, key, request_hash, created_at,"
                + " claimed_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT (account_id, key) DO NOTHING",
                accountId, key, requestHash, at, at) == 1) {
            return new Act();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT request_hash, response_status,"
                + " response_content_type, response_body, created_at, claimed_at FROM subscription.idempotency_key"
                + " WHERE account_id = ? AND key = ? FOR UPDATE", accountId, key);
        if (rows.isEmpty()) {
            // Released between the insert and the read: the client may simply try again.
            return new InProgress();
        }
        Map<String, Object> row = rows.getFirst();
        if (!instant(row, "created_at").plus(keptFor).isAfter(now)) {
            jdbc.update("UPDATE subscription.idempotency_key SET request_hash = ?, created_at = ?, claimed_at = ?,"
                    + " response_status = NULL, response_content_type = NULL, response_body = NULL"
                    + " WHERE account_id = ? AND key = ?", requestHash, at, at, accountId, key);
            return new Act();
        }
        if (!requestHash.equals(row.get("request_hash"))) {
            return new Reused();
        }
        if (row.get("response_status") != null) {
            return new Replay((Integer) row.get("response_status"), (String) row.get("response_content_type"),
                    (String) row.get("response_body"));
        }
        if (instant(row, "claimed_at").plus(requestTimeout).isAfter(now)) {
            return new InProgress();
        }
        // The request holding the key started longer ago than the timeout: it died. Its business change, if
        // any, rolled back with its response, so this request acts again.
        jdbc.update("UPDATE subscription.idempotency_key SET claimed_at = ? WHERE account_id = ? AND key = ?",
                at, accountId, key);
        return new Act();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(UUID accountId, String key, int status, String contentType, String body) {
        jdbc.update("UPDATE subscription.idempotency_key SET response_status = ?, response_content_type = ?,"
                + " response_body = ? WHERE account_id = ? AND key = ?", status, contentType, body, accountId, key);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID accountId, String key) {
        jdbc.update("DELETE FROM subscription.idempotency_key WHERE account_id = ? AND key = ?"
                + " AND response_status IS NULL", accountId, key);
    }

    private static Instant instant(Map<String, Object> row, String column) {
        return ((Timestamp) row.get(column)).toInstant();
    }
}
