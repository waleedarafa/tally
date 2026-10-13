package com.tally.subscription.adapter.persistence;

import com.tally.platform.InvariantCheck;
import java.util.function.LongSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Subscription's business invariants (FR-024), checked daily on its own data. */
@Configuration(proxyBeanMethods = false)
class SubscriptionInvariants {

    static final String JOBS = "subscription.jobs";

    /**
     * D1: every subscription is one renewal ahead. Each renewal schedules the next one, so a renewal that is never
     * scheduled stops the subscription without a trace: no row is late, so D9's alarm cannot see it. Counts the
     * subscriptions with no unfinished renewal row for the period after their latest.
     *
     * <p>A FAILED row counts as scheduled: the renewal queue is a money queue, so {@code tally.queue.failed} already
     * reports it, and counting it here as well would raise a second alert for one fault. A DONE row for that period
     * does not count: the period it should have made does not exist, and nothing will renew the subscription.
     *
     * <p>Matches the renewal payload, {@code <subscription id>,<period number>}, written by
     * {@code Renewals.scheduleNext}; a change to that format must change this query too. Written as one anti-join
     * over the whole table, not a lookup per subscription, so it needs no index on the payload.
     */
    @Bean
    InvariantCheck nextRenewalScheduled(JdbcTemplate jdbc) {
        return check("subscription.next-renewal-scheduled", () -> jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT s.id::text || ',' || (coalesce(max(p.number), 0) + 1) AS payload
                    FROM subscription.subscription s
                    LEFT JOIN subscription.subscription_period p ON p.subscription_id = s.id
                    GROUP BY s.id) expected
                WHERE NOT EXISTS (SELECT 1 FROM subscription.renewal r
                                  WHERE r.payload = expected.payload AND r.status <> 'DONE')
                """, Long.class));
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
