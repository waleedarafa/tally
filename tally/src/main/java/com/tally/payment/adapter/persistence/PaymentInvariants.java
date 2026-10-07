package com.tally.payment.adapter.persistence;

import com.tally.platform.InvariantCheck;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.function.LongSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Payment's business invariants (ADR-024), checked daily on its own data. */
@Configuration(proxyBeanMethods = false)
class PaymentInvariants {

    static final String JOBS = "payment.jobs";

    /** D3: never charged twice. No reference has more than one successful attempt. */
    @Bean
    InvariantCheck duplicateSuccessfulCharges(JdbcTemplate jdbc) {
        return check("payment.duplicate-successful-charges", () -> jdbc.queryForObject("""
                SELECT count(*) FROM (SELECT reference FROM payment.payment_attempt
                 WHERE state = 'SUCCEEDED' GROUP BY reference HAVING count(*) > 1) duplicated
                """, Long.class));
    }

    /** D3: no UNKNOWN outcome after 24 hours. */
    @Bean
    InvariantCheck unknownOverADay(JdbcTemplate jdbc, Clock clock) {
        return check("payment.unknown-over-24h", () -> jdbc.queryForObject(
                "SELECT count(*) FROM payment.payment_attempt WHERE state = 'UNKNOWN' AND updated_at < ?", Long.class,
                clock.instant().minus(Duration.ofHours(24)).atOffset(ZoneOffset.UTC)));
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
