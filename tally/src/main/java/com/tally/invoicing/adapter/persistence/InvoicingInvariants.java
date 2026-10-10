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
     * D1, FR-024: no account has two invoices for overlapping periods. The unique constraint already
     * stops two invoices on one billing date; this also catches overlaps from any other cause.
     */
    @Bean
    InvariantCheck duplicateInvoices(JdbcTemplate jdbc) {
        return check("invoicing.duplicate-invoices", () -> jdbc.queryForObject("""
                SELECT count(*) FROM invoicing.invoice a
                JOIN invoicing.invoice b ON a.account_id = b.account_id AND a.id < b.id
                 AND a.period_start < b.period_end AND b.period_start < a.period_end
                """, Long.class));
    }

    /** FR-024: no zero or negative invoices. */
    @Bean
    InvariantCheck nonPositiveInvoices(JdbcTemplate jdbc) {
        return check("invoicing.non-positive-invoices",
                () -> jdbc.queryForObject("SELECT count(*) FROM invoicing.invoice WHERE amount_minor <= 0", Long.class));
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
