package com.tally.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.AbstractIntegrationTest;
import com.tally.support.ChaosDatabase;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR-041's migration (V14) on data left by the previous release: one collection request per
 * reference, built from its earliest attempt, with the outcome of its latest; every attempt linked.
 */
class CollectionRequestBackfillIT {

    static Flyway flyway(ChaosDatabase db, String target) {
        return Flyway.configure()
                .dataSource(db.url(), AbstractIntegrationTest.POSTGRES.getUsername(),
                        AbstractIntegrationTest.POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    static void attempt(JdbcTemplate jdbc, String reference, String state, long amount, String at) {
        jdbc.update("INSERT INTO payment.payment_attempt (id, reference, account_id, amount_minor, currency,"
                        + " payment_token, state, created_at, updated_at) VALUES (?, ?, ?, ?, 'USD', 'tok', ?, ?, ?)",
                UUID.randomUUID(), reference, UUID.randomUUID(), amount, state, OffsetDateTime.parse(at),
                OffsetDateTime.parse(at));
    }

    @Test
    void existingAttemptsGetOneRequestPerReference() {
        ChaosDatabase db = ChaosDatabase.create("backfill");
        flyway(db, "13").migrate();
        JdbcTemplate jdbc = db.jdbc();
        attempt(jdbc, "PAID", "SUCCEEDED", 999, "2026-01-01T00:00:00Z");
        attempt(jdbc, "DECLINED", "FAILED", 1999, "2026-01-01T00:00:00Z");
        attempt(jdbc, "UNKNOWN", "UNKNOWN", 999, "2026-01-01T00:00:00Z");
        attempt(jdbc, "PENDING", "PENDING", 999, "2026-01-01T00:00:00Z");
        // A reference tried twice: a failed attempt, then a later one that succeeded.
        attempt(jdbc, "RETRIED", "FAILED", 2999, "2026-01-01T00:00:00Z");
        attempt(jdbc, "RETRIED", "SUCCEEDED", 2999, "2026-01-02T00:00:00Z");

        flyway(db, "14").migrate();

        List<Map<String, Object>> requests = jdbc.queryForList(
                "SELECT reference, outcome, amount_minor FROM payment.collection_request ORDER BY reference");
        assertThat(requests).extracting(r -> r.get("reference") + "=" + r.get("outcome") + ":" + r.get("amount_minor"))
                .containsExactly("DECLINED=FAILED:1999", "PAID=SUCCEEDED:999", "PENDING=OPEN:999",
                        "RETRIED=SUCCEEDED:2999", "UNKNOWN=OPEN:999");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.payment_attempt WHERE request_id IS NULL",
                Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM payment.payment_attempt a
                JOIN payment.collection_request r ON r.id = a.request_id
                WHERE r.reference <> a.reference
                """, Long.class)).isZero();
    }
}
