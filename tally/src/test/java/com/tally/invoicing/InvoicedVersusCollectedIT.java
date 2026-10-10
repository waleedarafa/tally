package com.tally.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.AbstractIntegrationTest;
import com.tally.payment.PaymentApi;
import com.tally.platform.InvariantCheck;
import com.tally.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * FR-024, report Q4: collected versus invoiced. A PAID invoice must match what Payment collected
 * for its reference, and an unpaid one must have nothing collected.
 */
class InvoicedVersusCollectedIT extends AbstractIntegrationTest {

    static final String CHECK = "invoicing.invoiced-versus-collected";

    @Autowired
    List<InvariantCheck> checks;

    @Autowired
    PaymentApi payments;

    @Autowired
    JdbcTemplate jdbc;

    InvariantCheck check() {
        return checks.stream().filter(c -> c.name().equals(CHECK)).findFirst().orElseThrow();
    }

    @Test
    void countsInvoicesWhosePaymentDoesNotMatch() {
        long before = check().violations();
        Instant issued = clock.instant().minus(Duration.ofHours(3));

        invoice("PAID", 999, issued, collected(999));            // holds
        invoice("PAYMENT_PENDING", 999, issued, null);           // holds: nothing collected yet
        invoice("PAID", 999, issued, null);                      // paid, nothing collected
        invoice("PAID", 999, issued, collected(1999));           // paid, a different amount collected
        invoice("PAYMENT_FAILED", 999, issued, collected(999));  // collected, not marked paid
        // Issued half an hour ago: its payment event may still be on its way, so it is not checked yet.
        invoice("PAID", 999, clock.instant().minus(Duration.ofMinutes(30)), null);

        assertThat(check().violations()).isEqualTo(before + 3);
    }

    @Test
    void runsDailyInInvoicingsJobTable() {
        assertThat(check().table()).isEqualTo("invoicing.jobs");
        assertThat(jdbc.queryForList("SELECT queue_key FROM invoicing.jobs", String.class)).contains("invariant." + CHECK);
    }

    /** A reference whose attempt succeeded for this amount. */
    private String collected(long minorUnits) {
        String reference = "IVC-" + UUID.randomUUID();
        payments.requestCollection(reference, UUID.randomUUID(), Money.of(minorUnits, "USD"), "tok");
        jdbc.update("UPDATE payment.payment_attempt SET state = 'SUCCEEDED' WHERE reference = ?", reference);
        return reference;
    }

    private void invoice(String status, long minorUnits, Instant issuedAt, String reference) {
        UUID id = UUID.randomUUID();
        LocalDate start = LocalDate.of(2020, 1, 1).plusDays(Math.floorMod(id.getLeastSignificantBits(), 3000));
        jdbc.update("INSERT INTO invoicing.invoice (id, account_id, subscription_id, plan_id, status, amount_minor,"
                        + " currency, period_start, period_end, payment_reference, issued_at)"
                        + " VALUES (?, ?, ?, 'BASIC', ?, ?, 'USD', ?, ?, ?, ?)",
                id, UUID.randomUUID(), UUID.randomUUID(), status, minorUnits, start, start.plusMonths(1),
                reference != null ? reference : "IVC-" + id, issuedAt.atOffset(ZoneOffset.UTC));
    }
}
