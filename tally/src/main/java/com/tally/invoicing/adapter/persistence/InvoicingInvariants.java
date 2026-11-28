package com.tally.invoicing.adapter.persistence;

import com.tally.platform.InvariantCheck;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.function.LongSupplier;
import org.springframework.jdbc.core.JdbcTemplate;

/** Invoicing's business invariants (FR-024), checked daily on its own data. */
@Configuration(proxyBeanMethods = false)
class InvoicingInvariants {

    static final String JOBS = "invoicing.jobs";

    /**
     * D1, FR-024, ADR-046: no subscription has two period invoices for overlapping periods. The unique
     * constraint already stops two period invoices of one subscription for the same period number;
     * this also catches overlaps from any other cause. Adjustment invoices share a period invoice's
     * dates (ADR-040, I2) and are not period invoices, so they are excluded. Two period invoices of
     * different subscriptions of the same account may overlap: they can share the account billing day.
     */
    @Bean
    InvariantCheck duplicateInvoices(JdbcTemplate jdbc) {
        return check("invoicing.duplicate-invoices", () -> jdbc.queryForObject("""
                SELECT count(*) FROM invoicing.invoice a
                JOIN invoicing.invoice b ON a.subscription_id = b.subscription_id AND a.id < b.id
                 AND a.invoice_type = 'PERIOD' AND b.invoice_type = 'PERIOD'
                 AND a.period_start < b.period_end AND b.period_start < a.period_end
                """, Long.class));
    }

    /**
     * FR-024, changed in Slice 1R PR-5: no negative invoices. A zero invoice is valid (a free trial's
     * period); it is paid when issued and never reaches Payment.
     */
    @Bean
    InvariantCheck negativeInvoices(JdbcTemplate jdbc) {
        return check("invoicing.negative-invoices",
                () -> jdbc.queryForObject("SELECT count(*) FROM invoicing.invoice WHERE amount_minor < 0", Long.class));
    }

    static InvariantCheck check(String name, LongSupplier violations) {
        return new InvariantCheck() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String table() {
                return JOBS;
            }

            @Override
            public long violations() {
                return violations.getAsLong();
            }
        };
    }
}
