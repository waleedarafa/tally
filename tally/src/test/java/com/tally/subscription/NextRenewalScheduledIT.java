package com.tally.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.account.AccountApi;
import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.InvoicingApi;
import com.tally.platform.InvariantCheck;
import com.tally.support.AbstractProviderIntegrationTest;
import com.tally.support.FakeProvider;
import com.tally.support.Quiesce;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * D1, FR-024: every subscription is always one renewal ahead. Each renewal schedules the next one (H-03 of the
 * topic tracker), so a renewal that is never scheduled stops a subscription silently: the D9 alarm sees late work,
 * not work that was never written. The check {@code subscription.next-renewal-scheduled} counts subscriptions with
 * no unfinished renewal row for the period after their latest. Own context: it moves the clock.
 */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms",
        "tally.test.context=next-renewal-scheduled"
})
class NextRenewalScheduledIT extends AbstractProviderIntegrationTest {

    @Autowired
    AccountApi accounts;

    @Autowired
    SubscriptionApi subscriptions;

    @Autowired
    InvoicingApi invoicing;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    @Qualifier("nextRenewalScheduled")
    InvariantCheck nextRenewalScheduled;

    UUID subscribedAccount() {
        UUID account = accounts.open(Currency.getInstance("AED"), ZoneId.of("UTC")).id();
        accounts.recordPaymentToken(account, FakeProvider.token());
        subscriptions.subscribe(account, "BASIC");
        return account;
    }

    LocalDate firstPeriodStart(UUID account) {
        await().atMost(Duration.ofSeconds(15)).until(() -> invoicing.invoicesFor(account).stream()
                .anyMatch(i -> i.status() == InvoiceStatus.PAID));
        return invoicing.invoicesFor(account).getFirst().periodStart();
    }

    @Test
    void aSubscriptionStaysOneRenewalAheadThroughSignUpAndRenewal() {
        long before = nextRenewalScheduled.violations();

        UUID account = subscribedAccount();
        LocalDate renewal = firstPeriodStart(account).plusMonths(1);
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(10));
        assertThat(nextRenewalScheduled.violations()).isEqualTo(before);

        // Renew into period 2: the renewal row for period 2 is done, and period 3's row exists.
        clock.set(renewal.atStartOfDay(ZoneId.of("UTC")).toInstant());
        await().atMost(Duration.ofSeconds(15)).until(() -> invoicing.invoicesFor(account).size() == 2);
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(10));
        assertThat(nextRenewalScheduled.violations()).isEqualTo(before);
    }

    @Test
    void aSubscriptionWhoseNextRenewalWasNeverWrittenIsCounted() {
        UUID account = subscribedAccount();
        firstPeriodStart(account);
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(10));
        long before = nextRenewalScheduled.violations();

        // As if a release had skipped scheduleNext: the subscription has no renewal row at all.
        jdbc.update("DELETE FROM subscription.renewal WHERE queue_key LIKE ?", account + ":%");

        assertThat(nextRenewalScheduled.violations()).isEqualTo(before + 1);
    }
}
