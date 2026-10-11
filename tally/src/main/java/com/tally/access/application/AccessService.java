package com.tally.access.application;

import com.tally.access.AccessApi;
import com.tally.subscription.SubscriptionChangeType;
import com.tally.subscription.SubscriptionChanged;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

/**
 * Plain layered code (FR-003). Access reacts to events and answers from its own tables; it never calls a
 * billing API (Slice 1R PR-10). A subscription gives access from sign-up, trial included, without waiting
 * for a first payment (H7). Access is granted once per account; granting again changes nothing, so
 * redelivered events are harmless (FR-009). Slice 1R never revokes access.
 */
@Service
class AccessService implements AccessApi {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    AccessService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public boolean hasAccess(UUID accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM access.access WHERE account_id = ?)", Boolean.class, accountId));
    }

    @ApplicationModuleListener
    void on(SubscriptionChanged changed) {
        if (changed.type() == SubscriptionChangeType.STARTED) {
            grant(changed.accountId(), "SUBSCRIPTION_STARTED");
        }
    }

    private void grant(UUID accountId, String grantedBy) {
        jdbc.update("INSERT INTO access.access (account_id, granted_at, granted_by) VALUES (?, ?, ?)"
                + " ON CONFLICT (account_id) DO NOTHING",
                accountId, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), grantedBy);
    }
}
