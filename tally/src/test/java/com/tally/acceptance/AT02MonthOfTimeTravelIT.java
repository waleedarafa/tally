package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.account.AccountApi;
import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.InvoiceView;
import com.tally.invoicing.InvoicingApi;
import com.tally.subscription.SubscriptionApi;
import com.tally.support.AbstractProviderIntegrationTest;
import com.tally.support.FakeProvider;
import com.tally.support.Quiesce;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * AT-2. One month of time travel, 100 accounts (D1, D4). Pass when: exactly one new invoice and one
 * new payment attempt per account, and the test takes under 2 minutes.
 *
 * <p>Pollers run on their production settings (1 s poll interval, 4 billing and 15 payment workers).
 */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.test.context=at02"
})
class AT02MonthOfTimeTravelIT extends AbstractProviderIntegrationTest {

    static final int ACCOUNTS = 100;
    static final List<String> ZONES = List.of("UTC", "Asia/Dubai", "America/New_York", "Europe/London", "Asia/Tokyo");

    @Autowired
    AccountApi accounts;

    @Autowired
    SubscriptionApi subscriptions;

    @Autowired
    InvoicingApi invoicing;

    @Autowired
    JdbcTemplate jdbc;

    int attempts(UUID account) {
        return jdbc.queryForObject("SELECT count(*) FROM payment.payment_attempt WHERE account_id = ?",
                Integer.class, account);
    }

    boolean allPaid(List<UUID> all, int invoicesEach) {
        return all.stream().allMatch(a -> {
            List<InvoiceView> invoices = invoicing.invoicesFor(a);
            return invoices.size() == invoicesEach && invoices.stream().allMatch(i -> i.status() == InvoiceStatus.PAID);
        });
    }

    @Test
    void oneMonthLaterEveryAccountHasExactlyOneNewInvoiceAndOneNewAttempt() {
        long started = System.nanoTime();
        List<UUID> all = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            UUID account = accounts.open(Currency.getInstance(i % 2 == 0 ? "USD" : "AED"),
                    ZoneId.of(ZONES.get(i % ZONES.size()))).id();
            accounts.recordPaymentToken(account, FakeProvider.token());
            subscriptions.subscribe(account, List.of("BASIC", "STANDARD", "PREMIUM").get(i % 3));
            all.add(account);
        }
        await().atMost(Duration.ofSeconds(60)).until(() -> allPaid(all, 1));
        Quiesce.untilIdle(jdbc, clock, all, Duration.ofSeconds(30));
        assertThat(all).allSatisfy(a -> assertThat(attempts(a)).isEqualTo(1));

        // One month of time travel: past every account's local-midnight renewal, short of the next.
        Instant oneMonthLater = clock.instant().atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
        clock.set(oneMonthLater);

        await().atMost(Duration.ofSeconds(60)).until(() -> allPaid(all, 2));
        Quiesce.untilIdle(jdbc, clock, all, Duration.ofSeconds(30));
        // Nothing more arrives afterwards.
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10)).until(() -> allPaid(all, 2));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        for (UUID account : all) {
            List<InvoiceView> invoices = invoicing.invoicesFor(account);
            assertThat(invoices).hasSize(2);
            assertThat(invoices.get(1).periodStart()).isEqualTo(invoices.get(0).periodEnd());
            assertThat(attempts(account)).isEqualTo(2);
            assertThat(FakeProvider.chargeCount(invoices.get(1).paymentReference())).isEqualTo(1);
        }
        assertThat(elapsed).isLessThan(Duration.ofMinutes(2));
        System.out.printf("AT-2: %d accounts, one month of time travel, %d s%n", ACCOUNTS, elapsed.toSeconds());
    }
}
