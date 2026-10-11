package com.tally.subscription.adapter.persistence;

import com.tally.subscription.domain.IdempotentRequests;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link IdempotentRequests} in {@code subscription.idempotency_key}. The claim is an atomic insert on the
 * key (the primary key {@code idempotency_key_pkey} is the guard); an existing row is read under a row
 * lock, so a stale key is taken over by one request only.
 */
@Repository
class JdbcIdempotentRequests implements IdempotentRequests {

    private final JdbcTemplate jdbc;

    JdbcIdempotentRequests(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim claim(String key, String requestHash, Instant now, Duration keptFor) {
        int inserted = jdbc.update("INSERT INTO subscription.idempotency_key (key, request_hash, created_at)"
                + " VALUES (?, ?, ?) ON CONFLICT (key) DO NOTHING", key, requestHash, Timestamp.from(now));
        if (inserted == 1) {
            return new Act();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT request_hash, response_status,"
                + " response_content_type, response_body, created_at FROM subscription.idempotency_key"
                + " WHERE key = ? FOR UPDATE", key);
        if (rows.isEmpty()) {
            // Released between the insert and the read: the client may simply try again.
            return new InProgress();
        }
        Map<String, Object> row = rows.getFirst();
        Instant createdAt = ((Timestamp) row.get("created_at")).toInstant();
        if (!createdAt.plus(keptFor).isAfter(now)) {
            jdbc.update("UPDATE subscription.idempotency_key SET request_hash = ?, created_at = ?,"
                    + " response_status = NULL, response_content_type = NULL, response_body = NULL WHERE key = ?",
                    requestHash, Timestamp.from(now), key);
            return new Act();
        }
        if (!requestHash.equals(row.get("request_hash"))) {
            return new Reused();
        }
        if (row.get("response_status") == null) {
            return new InProgress();
        }
        return new Replay((Integer) row.get("response_status"), (String) row.get("response_content_type"),
                (String) row.get("response_body"));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String key, int status, String contentType, String body) {
        jdbc.update("UPDATE subscription.idempotency_key SET response_status = ?, response_content_type = ?,"
                + " response_body = ? WHERE key = ?", status, contentType, body, key);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String key) {
        jdbc.update("DELETE FROM subscription.idempotency_key WHERE key = ? AND response_status IS NULL", key);
    }
}
