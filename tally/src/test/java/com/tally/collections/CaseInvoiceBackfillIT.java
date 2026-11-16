package com.tally.collections;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.AbstractIntegrationTest;
import com.tally.support.ChaosDatabase;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Slice 3 PR-3's migration (V39) on the cases of the previous release: each case keeps its one invoice, unpaid
 * while the case is open, paid when it closed. The open cases of today stay open, with the same invoice.
 */
class CaseInvoiceBackfillIT {

    static Flyway flyway(ChaosDatabase db, String target) {
        return Flyway.configure()
                .dataSource(db.url(), AbstractIntegrationTest.POSTGRES.getUsername(),
                        AbstractIntegrationTest.POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    static UUID oneCase(JdbcTemplate jdbc, UUID invoice, String status, String closedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO collections.collection_case (id, account_id, invoice_id, status, opened_at,"
                        + " closed_at, version) VALUES (?, ?, ?, ?, ?, ?, 0)", id, UUID.randomUUID(), invoice, status,
                OffsetDateTime.parse("2026-11-01T00:00:00Z"), closedAt == null ? null : OffsetDateTime.parse(closedAt));
        return id;
    }

    @Test
    void eachCaseKeepsItsInvoice() {
        ChaosDatabase db = ChaosDatabase.create("case_invoice_backfill");
        flyway(db, "38").migrate();
        JdbcTemplate jdbc = db.jdbc();
        UUID openInvoice = UUID.randomUUID();
        UUID closedInvoice = UUID.randomUUID();
        UUID open = oneCase(jdbc, openInvoice, "OPEN", null);
        UUID closed = oneCase(jdbc, closedInvoice, "CLOSED", "2026-11-04T00:00:00Z");

        flyway(db, "39").migrate();

        assertThat(jdbc.queryForList("SELECT case_id || ' ' || invoice_id || ' ' || coalesce(paid_at::text, 'unpaid')"
                + " FROM collections.case_invoice ORDER BY paid_at NULLS FIRST", String.class))
                .containsExactly(open + " " + openInvoice + " unpaid",
                        closed + " " + closedInvoice + " " + jdbc.queryForObject(
                                "SELECT closed_at::text FROM collections.collection_case WHERE id = ?", String.class,
                                closed));
        assertThat(jdbc.queryForObject("SELECT status FROM collections.collection_case WHERE id = ?", String.class,
                open)).isEqualTo("OPEN");
    }

    /** Slice 3 PR-4's migration (V40): an open case of today, which had only its day-3 retry, gets the rest. */
    @Test
    void anOpenCaseOfTodayGetsTheRestOfTheTimetable() {
        ChaosDatabase db = ChaosDatabase.create("case_timetable_backfill");
        flyway(db, "39").migrate();
        JdbcTemplate jdbc = db.jdbc();
        UUID open = oneCase(jdbc, UUID.randomUUID(), "OPEN", null);
        oneCase(jdbc, UUID.randomUUID(), "CLOSED", "2026-11-04T00:00:00Z");

        flyway(db, "40").migrate();

        assertThat(jdbc.queryForList("SELECT queue_key || ' ' || payload || ' ' || (due_at - '2026-11-01T00:00:00Z')"
                + " FROM (SELECT queue_key, payload, due_at FROM collections.retry_queue UNION ALL"
                + " SELECT queue_key, payload, due_at FROM collections.timer_queue) q ORDER BY due_at", String.class))
                .as("the closed case gets none")
                .containsExactly(open + ":2 " + open + ",2 7 days",
                        open + ":OVERDUE " + open + ",OVERDUE 7 days 01:00:00",
                        open + ":BLOCKED " + open + ",BLOCKED 10 days",
                        open + ":WRITTEN_OFF " + open + ",WRITTEN_OFF 30 days");
    }
}
