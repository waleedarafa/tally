package com.tally.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.account.AccountApi;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** Renewals run from the billing schedule queue (ADR-002, ADR-017). Own context: it moves the clock. */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms",
        "tally.test.context=renewal"
})
class RenewalIT extends AbstractProviderIntegrationTest {

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

    Instant dueAt(UUID account, LocalDate billingDate) {
        return jdbc.queryForObject("SELECT due_at FROM invoicing.billing_schedule WHERE queue_key = ?",
                OffsetDateTime.class, account + ":" + billingDate).toInstant();
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
        jdbc.update("UPDATE invoicing.billing_schedule SET status = 'READY' WHERE queue_key = ?",
                account + ":" + renewal);

        await().atMost(Duration.ofSeconds(10)).until(() -> "DONE".equals(jdbc.queryForObject(
                "SELECT status FROM invoicing.billing_schedule WHERE queue_key = ?", String.class, account + ":" + renewal)));
        assertThat(invoicing.invoicesFor(account)).hasSize(2);
        // The renewal date and the one after it; the first invoice was issued at subscribe, not from the schedule.
        assertThat(jdbc.queryForList("SELECT queue_key FROM invoicing.billing_schedule WHERE queue_key LIKE ?",
                String.class, account + ":%"))
                .containsExactlyInAnyOrder(account + ":" + renewal, account + ":" + renewal.plusMonths(1));
        assertThat(FakeProvider.chargeCount(invoicing.invoicesFor(account).get(1).paymentReference())).isEqualTo(1);
    }
}
