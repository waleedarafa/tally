package com.tally.support;

import static org.awaitility.Awaitility.await;

import java.sql.Array;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Waits until the given accounts have no work in progress: no billing item claimed or due, no
 * payment attempt PENDING or IN_FLIGHT. Time-travel tests call this before every clock jump: jumping
 * while a claim is held would make the claim look stale to the reaper, which no real clock does.
 */
public final class Quiesce {

    private Quiesce() {
    }

    public static void untilIdle(JdbcTemplate jdbc, Clock clock, Collection<UUID> accounts, Duration atMost) {
        await().atMost(atMost).pollInterval(Duration.ofMillis(100)).until(() -> busy(jdbc, clock, accounts) == 0);
    }

    private static int busy(JdbcTemplate jdbc, Clock clock, Collection<UUID> accounts) {
        return jdbc.execute((Connection c) -> {
            Array ids = c.createArrayOf("uuid", accounts.toArray());
            Array keys = c.createArrayOf("text", accounts.stream().map(UUID::toString).toArray());
            try (var billing = c.prepareStatement("SELECT count(*) FROM invoicing.billing_schedule"
                    + " WHERE split_part(queue_key, ':', 1) = ANY (?)"
                    + " AND (status = 'IN_PROCESSING' OR (status = 'READY' AND due_at <= ?))");
                 var attempts = c.prepareStatement("SELECT count(*) FROM payment.payment_attempt"
                    + " WHERE account_id = ANY (?) AND state IN ('PENDING', 'IN_FLIGHT')")) {
                billing.setArray(1, keys);
                billing.setObject(2, clock.instant().atOffset(ZoneOffset.UTC));
                attempts.setArray(1, ids);
                int busy = 0;
                for (var statement : List.of(billing, attempts)) {
                    try (var rs = statement.executeQuery()) {
                        rs.next();
                        busy += rs.getInt(1);
                    }
                }
                return busy;
            }
        });
    }
}
