package com.tally.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.account.AccountApi;
import com.tally.platform.internal.QueueFaults;
import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.InvoiceView;
import com.tally.invoicing.InvoicingApi;
import com.tally.shared.Money;
import com.tally.subscription.SubscriptionApi;
import com.tally.support.AbstractProviderIntegrationTest;
import com.tally.support.FakeProvider;
import com.tally.support.Quiesce;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** Renewals run from Subscription's renewal queue (Design Record §12, FR-017). Own context: it moves the clock. */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms",
        "tally.test.context=renewal"
})
class RenewalIT extends AbstractProviderIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    AccountApi accounts;

    @Autowired
    SubscriptionApi subscriptions;

    @Autowired
    InvoicingApi invoicing;

    @Autowired
    JdbcTemplate jdbc;

    UUID subscribedAccount(String zone) {
        UUID account = accounts.open(Currency.getInstance("AED"), ZoneId.of(zone)).id();
        accounts.recordPaymentToken(account, FakeProvider.token());
        subscriptions.subscribe(account, "BASIC");
        return account;
    }

    List<InvoiceView> paidInvoices(UUID account, int count) {
        await().atMost(Duration.ofSeconds(15)).until(() -> invoicing.invoicesFor(account).stream()
                .filter(i -> i.status() == InvoiceStatus.PAID).count() == count);
        return invoicing.invoicesFor(account);
    }

    /** A renewal's key: {@code <account>:<subscription>:<period end>} (D-26). */
    String key(UUID account, LocalDate billingDate) {
        UUID subscription = jdbc.queryForObject("SELECT id FROM subscription.subscription WHERE account_id = ?",
                UUID.class, account);
        return account + ":" + subscription + ":" + billingDate;
    }

    Instant dueAt(UUID account, LocalDate billingDate) {
        return jdbc.queryForObject("SELECT due_at FROM subscription.renewal WHERE queue_key = ?",
                OffsetDateTime.class, key(account, billingDate)).toInstant();
    }

    @Test
    void theNextBillingDateIsScheduledAtLocalMidnightAndRenewsOnTime() {
        UUID account = subscribedAccount("Asia/Dubai");
        LocalDate start = paidInvoices(account, 1).getFirst().periodStart();
        LocalDate renewal = start.plusMonths(1);
        Instant midnight = renewal.atStartOfDay(ZoneId.of("Asia/Dubai")).toInstant();
        assertThat(dueAt(account, renewal)).isEqualTo(midnight);
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(10));

        // One second before local midnight: nothing happens.
        clock.set(midnight.minusSeconds(1));
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                .until(() -> invoicing.invoicesFor(account).size() == 1);

        clock.set(midnight);
        List<InvoiceView> invoices = paidInvoices(account, 2);
        InvoiceView renewed = invoices.get(1);
        assertThat(renewed.periodStart()).isEqualTo(renewal);
        assertThat(renewed.periodEnd()).isEqualTo(renewal.plusMonths(1));
        assertThat(renewed.amount()).isEqualTo(Money.of(3699, "AED"));
        assertThat(FakeProvider.chargeCount(renewed.paymentReference())).isEqualTo(1);
        assertThat(dueAt(account, renewal.plusMonths(1)))
                .isEqualTo(renewal.plusMonths(1).atStartOfDay(ZoneId.of("Asia/Dubai")).toInstant());
    }

    @Test
    void aRenewalDeliveredTwiceIssuesOneInvoiceAndSchedulesOneNextDate() {
        UUID account = subscribedAccount("UTC");
        LocalDate renewal = paidInvoices(account, 1).getFirst().periodStart().plusMonths(1);
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(10));
        clock.set(renewal.atStartOfDay(ZoneId.of("UTC")).toInstant());
        paidInvoices(account, 2);
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(10));

        // As if the instance crashed after committing the renewal, before marking the item done.
        QueueFaults.of(context).redeliver("renewals", key(account, renewal));

        await().atMost(Duration.ofSeconds(10)).until(() -> "DONE".equals(jdbc.queryForObject(
                "SELECT status FROM subscription.renewal WHERE queue_key = ?", String.class, key(account, renewal))));
        assertThat(invoicing.invoicesFor(account)).hasSize(2);
        // The renewal date and the one after it; the first invoice was issued at subscribe, not from the schedule.
        assertThat(jdbc.queryForList("SELECT queue_key FROM subscription.renewal WHERE queue_key LIKE ?",
                String.class, account + ":%"))
                .containsExactlyInAnyOrder(key(account, renewal), key(account, renewal.plusMonths(1)));
        assertThat(FakeProvider.chargeCount(invoicing.invoicesFor(account).get(1).paymentReference())).isEqualTo(1);
    }

    /** ENDED refuses a renewal (design.md §5): the renewal row that waits runs, and does nothing. */
    @Test
    void theRenewalOfAnEndedSubscriptionRunsAndDoesNothing() {
        UUID account = subscribedAccount("UTC");
        LocalDate renewal = paidInvoices(account, 1).getFirst().periodStart().plusMonths(1);
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(10));
        subscriptions.endLiveSubscriptions(account, EndReason.WRITE_OFF);

        clock.set(renewal.atStartOfDay(ZoneId.of("UTC")).toInstant());
        await().atMost(Duration.ofSeconds(10)).until(() -> "DONE".equals(jdbc.queryForObject(
                "SELECT status FROM subscription.renewal WHERE queue_key = ?", String.class, key(account, renewal))));
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(10));

        assertThat(invoicing.invoicesFor(account)).as("no invoice after the end").hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription.subscription_period p"
                + " JOIN subscription.subscription s ON s.id = p.subscription_id WHERE s.account_id = ?", Long.class,
                account)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription.renewal WHERE queue_key LIKE ?",
                Long.class, account + ":%")).as("and no next renewal").isEqualTo(1);
    }
}
