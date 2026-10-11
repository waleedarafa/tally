package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.AbstractIntegrationTest;
import com.tally.support.SharedJob;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * AT-12. Invariant check (D1, FR-024). Pass when: an injected duplicate invoice raises
 * {@code tally.invariant.violations}. The checks run daily, as jobs in each module's own job table.
 */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms",
        "tally.test.context=at12"
})
class AT12InvariantCheckIT extends AbstractIntegrationTest {

    static final String JOB = "invariant.invoicing.duplicate-invoices";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MeterRegistry meters;

    /** Job rows are shared by every test context: bring this one due at this context's clock. */
    @BeforeEach
    void checkDueNow() {
        jdbc.update("UPDATE invoicing.jobs SET status = 'READY', claim_token = NULL, due_at = ?, last_success_at = NULL"
                + " WHERE queue_key = ?",
                clock.instant().atOffset(ZoneOffset.UTC), JOB);
    }

    double violations() {
        return meters.get("tally.invariant.violations").tag("check", "invoicing.duplicate-invoices").gauge().value();
    }

    void runCheck() {
        Instant now = clock.instant();
        SharedJob.invariantCheck(JOB).await(jdbc, clock, Duration.ofSeconds(10), () -> {
            OffsetDateTime at = jdbc.queryForObject("SELECT last_success_at FROM invoicing.jobs WHERE queue_key = ?",
                    OffsetDateTime.class, JOB);
            return at != null && !at.toInstant().isBefore(now);
        });
    }

    void invoice(UUID account, String start, String end) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO invoicing.invoice (id, account_id, subscription_id, plan_id, status, amount_minor,"
                        + " currency, period_start, period_end, payment_reference, issued_at)"
                        + " VALUES (?, ?, ?, 'BASIC', 'PAID', 999, 'USD', ?, ?, ?, ?)",
                id, account, UUID.randomUUID(), LocalDate.parse(start), LocalDate.parse(end), "INV-" + id,
                clock.instant().atOffset(ZoneOffset.UTC));
    }

    @Test
    void anInjectedDuplicateInvoiceRaisesTheViolationsMetric() {
        runCheck();
        double before = violations();

        // A second invoice for a period the account was already invoiced for, on another start date,
        // so the unique constraint on (account, period start) does not stop it.
        UUID account = UUID.randomUUID();
        invoice(account, "2026-01-15", "2026-02-15");
        invoice(account, "2026-01-20", "2026-02-20");

        clock.advance(Duration.ofDays(1));
        runCheck();

        assertThat(violations()).isEqualTo(before + 1);
    }
}
