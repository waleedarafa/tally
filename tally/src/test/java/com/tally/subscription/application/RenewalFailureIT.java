package com.tally.subscription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tally.AbstractIntegrationTest;
import com.tally.account.AccountApi;
import com.tally.platform.Outcome;
import com.tally.platform.QueueItem;
import com.tally.subscription.SubscriptionApi;
import com.tally.subscription.SubscriptionView;
import java.time.ZoneId;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CLAUDE.md rule 11: only the guarded fact counts as a duplicate. A renewal that fails on any other
 * constraint is rolled back and rethrown, so the queue retries it and its health shows it; it is never
 * marked done as if another delivery had renewed (D1).
 */
class RenewalFailureIT extends AbstractIntegrationTest {

    @Autowired
    AccountApi accounts;

    @Autowired
    SubscriptionApi subscriptions;

    @Autowired
    Renewals renewals;

    @Autowired
    JdbcTemplate jdbc;

    long periods(UUID subscription) {
        return jdbc.queryForObject("SELECT count(*) FROM subscription.subscription_period WHERE subscription_id = ?",
                Long.class, subscription);
    }

    @Test
    void aRenewalFailingAnotherConstraintIsNotDoneAndSucceedsOnRetry() {
        UUID account = accounts.open(Currency.getInstance("USD"), ZoneId.of("UTC")).id();
        SubscriptionView subscription = subscriptions.subscribe(account, "BASIC");
        QueueItem item = new QueueItem(1, account + ":renewal", clock.instant(), subscription.id() + ",2", 1);
        // A different constraint fails, for this subscription only: its renewal cannot be recorded.
        jdbc.execute("ALTER TABLE subscription.subscription_change ADD CONSTRAINT test_refuses_renewal"
                + " CHECK (subscription_id <> '" + subscription.id() + "' OR type <> 'PERIOD_RENEWED')");
        try {
            assertThatThrownBy(() -> renewals.handle(item))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("test_refuses_renewal");
            assertThat(periods(subscription.id())).as("rolled back, not renewed").isEqualTo(1);
        } finally {
            jdbc.execute("ALTER TABLE subscription.subscription_change DROP CONSTRAINT test_refuses_renewal");
        }

        // The queue retries the item: now it renews.
        assertThat(renewals.handle(item)).isEqualTo(Outcome.done());
        assertThat(periods(subscription.id())).isEqualTo(2);
    }
}
