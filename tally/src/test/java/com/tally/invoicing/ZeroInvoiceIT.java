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
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * A zero invoice (a free trial's period) is issued, paid at once, and publishes InvoicePaid; it creates no
 * collection request and never reaches Payment. Approving the free-trial kind is a data change, not a
 * release (D4), so this must work before finance approves it. Own context: it moves the clock.
 */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms",
        "tally.test.context=zero-invoice"
})
class ZeroInvoiceIT extends AbstractProviderIntegrationTest {

    /** Nothing listens to InvoicePaid yet, so the registry stores no publication of it; record it here. */
    @TestConfiguration(proxyBeanMethods = false)
    static class PaidInvoices {

        static final List<InvoicePaid> SEEN = new CopyOnWriteArrayList<>();

        @EventListener
        void on(InvoicePaid paid) {
            SEEN.add(paid);
        }
    }

    @Autowired
    AccountApi accounts;

    @Autowired
    SubscriptionApi subscriptions;

    @Autowired
    InvoicingApi invoicing;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void unapprove() {
        jdbc.update("UPDATE offers.offer_kind SET status = 'EXPERIMENTAL' WHERE id = 'free-trial'");
    }

    long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void aFreePeriodIsPaidWithoutPaymentAndTheNextIsCharged() {
        // Finance approves the kind and marketing publishes an offer of it: data, no release.
        jdbc.update("UPDATE offers.offer_kind SET status = 'APPROVED' WHERE id = 'free-trial'");
        jdbc.update("INSERT INTO offers.offer (id, name) VALUES ('TRIAL', 'One month free')");
        jdbc.update("INSERT INTO offers.offer_version (offer_id, version, kind, free_periods, published_at)"
                + " VALUES ('TRIAL', 1, 'free-trial', 1, now())");
        jdbc.update("INSERT INTO offers.offer_version_price (offer_id, version, currency, amount_minor)"
                + " VALUES ('TRIAL', 1, 'AED', 3699)");
        UUID account = accounts.open(Currency.getInstance("AED"), ZoneId.of("Asia/Dubai")).id();
        accounts.recordPaymentToken(account, FakeProvider.token());

        subscriptions.subscribe(account, "TRIAL");

        await().atMost(Duration.ofSeconds(10)).until(() -> !invoicing.invoicesFor(account).isEmpty());
        InvoiceView free = invoicing.invoicesFor(account).getFirst();
        await().atMost(Duration.ofSeconds(10)).until(() -> PaidInvoices.SEEN.stream()
                .anyMatch(paid -> paid.invoiceId().equals(free.id())));
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(10));
        System.out.println("ZERO period 1: " + free.status() + " " + free.amount() + " lines=" + free.lines());
        assertThat(free.amount()).isEqualTo(Money.of(0, "AED"));
        assertThat(free.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(free.lines()).hasSize(2);
        assertThat(count("SELECT count(*) FROM payment.collection_request WHERE reference = ?",
                free.paymentReference())).as("collection requests").isZero();
        assertThat(FakeProvider.requestCount(free.paymentReference())).as("provider requests").isZero();

        clock.set(free.periodEnd().atStartOfDay(ZoneId.of("Asia/Dubai")).toInstant());
        await().atMost(Duration.ofSeconds(15)).until(() -> invoicing.invoicesFor(account).size() == 2
                && invoicing.invoicesFor(account).get(1).status() == InvoiceStatus.PAID);
        InvoiceView charged = invoicing.invoicesFor(account).get(1);
        System.out.println("ZERO period 2: " + charged.status() + " " + charged.amount());
        assertThat(charged.amount()).isEqualTo(Money.of(3699, "AED"));
        assertThat(FakeProvider.chargeCount(charged.paymentReference())).isEqualTo(1);
    }
}
