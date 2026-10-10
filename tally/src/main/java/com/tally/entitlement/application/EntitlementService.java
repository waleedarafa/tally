package com.tally.entitlement.application;

import com.tally.entitlement.EntitlementApi;
import com.tally.payment.PaymentSucceeded;
import com.tally.subscription.SubscriptionChangeType;
import com.tally.subscription.SubscriptionChanged;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

/**
 * Plain layered code (FR-003). Access is granted once per account; granting again changes nothing,
 * so redelivered events are harmless (FR-009). Slice 1 never revokes access (no dunning).
 */
@Service
@EnableConfigurationProperties(AccessProperties.class)
class EntitlementService implements EntitlementApi {

    private final JdbcTemplate jdbc;
    private final AccessProperties properties;
    private final Clock clock;

    EntitlementService(JdbcTemplate jdbc, AccessProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public boolean hasAccess(UUID accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM entitlement.access WHERE account_id = ?)", Boolean.class, accountId));
    }

    @ApplicationModuleListener
    void on(SubscriptionChanged changed) {
        if (changed.type() == SubscriptionChangeType.STARTED && properties.grantBeforePayment()) {
            grant(changed.accountId(), "SUBSCRIPTION_STARTED");
        }
    }

    @ApplicationModuleListener
    void on(PaymentSucceeded succeeded) {
        grant(succeeded.accountId(), "PAYMENT_SUCCEEDED");
    }

    private void grant(UUID accountId, String grantedBy) {
        jdbc.update("INSERT INTO entitlement.access (account_id, granted_at, granted_by) VALUES (?, ?, ?)"
                + " ON CONFLICT (account_id) DO NOTHING",
                accountId, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), grantedBy);
    }
}
