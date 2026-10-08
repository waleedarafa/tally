package com.tally.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.AbstractIntegrationTest;
import com.tally.support.ChaosDatabase;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V15 on data left by the previous release: a renewal still in the billing queue gets the item's due
 * time; a first invoice, or a renewal whose queue row retention deleted, gets its issue time.
 */
class BilledForBackfillIT {

    static Flyway flyway(ChaosDatabase db, String target) {
        return Flyway.configure()
                .dataSource(db.url(), AbstractIntegrationTest.POSTGRES.getUsername(),
                        AbstractIntegrationTest.POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    static UUID invoice(JdbcTemplate jdbc, UUID account, String periodStart, String issuedAt) {
        UUID id = UUID.randomUUID();
        LocalDate start = LocalDate.parse(periodStart);
        jdbc.update("INSERT INTO invoicing.invoice (id, account_id, subscription_id, plan_id, status, amount_minor,"
                        + " currency, period_start, period_end, payment_reference, issued_at)"
                        + " VALUES (?, ?, ?, 'BASIC', 'PAID', 999, 'USD', ?, ?, ?, ?)",
                id, account, UUID.randomUUID(), start, start.plusMonths(1), "INV-" + id, OffsetDateTime.parse(issuedAt));
        return id;
    }

    static OffsetDateTime billedFor(JdbcTemplate jdbc, UUID invoice) {
        return jdbc.queryForObject("SELECT billed_for FROM invoicing.invoice WHERE id = ?", OffsetDateTime.class,
                invoice);
    }

    @Test
    void existingInvoicesGetTheirBillingTime() {
        ChaosDatabase db = ChaosDatabase.create("billedfor");
        flyway(db, "13").migrate();
        JdbcTemplate jdbc = db.jdbc();
        UUID account = UUID.randomUUID();
        UUID first = invoice(jdbc, account, "2026-01-31", "2026-01-31T15:00:00Z");
        UUID inQueue = invoice(jdbc, account, "2026-02-28", "2026-02-28T05:00:03Z");
        UUID retained = invoice(jdbc, account, "2026-03-31", "2026-03-31T04:00:02Z");
        jdbc.update("INSERT INTO invoicing.billing_schedule (queue_key, due_at, payload, status, attempts, created_at)"
                        + " VALUES (?, ?, '', 'DONE', 1, ?)", account + ":2026-02-28",
                OffsetDateTime.parse("2026-02-28T05:00:00Z"), OffsetDateTime.parse("2026-01-31T15:00:00Z"));

        flyway(db, "15").migrate();

        assertThat(billedFor(jdbc, first).toInstant()).hasToString("2026-01-31T15:00:00Z");
        assertThat(billedFor(jdbc, inQueue).toInstant()).hasToString("2026-02-28T05:00:00Z");
        assertThat(billedFor(jdbc, retained).toInstant()).hasToString("2026-03-31T04:00:02Z");
    }
}
