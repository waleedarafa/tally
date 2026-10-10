package com.tally.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.account.AccountApi;
import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.InvoiceView;
import com.tally.invoicing.InvoicingApi;
import com.tally.shared.Money;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Decision A (a clarification of O3): a subscription keeps the offer version it signed up to. A new
 * version applies to new sign-ups only. Own context: it moves the clock.
 */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms",
        "tally.test.context=offer-version-pinned"
})
class OfferVersionPinnedIT extends AbstractProviderIntegrationTest {

    @Autowired
    AccountApi accounts;

    @Autowired
    SubscriptionApi subscriptions;

    @Autowired
    InvoicingApi invoicing;

    @Autowired
    JdbcTemplate jdbc;

    /** Publishes a version of the test offer, as marketing would: a data change, not a release (D4). */
    void publish(int version, long usdMinorUnits) {
        if (version == 1) {
            jdbc.update("INSERT INTO offers.offer (id, name) VALUES ('PINNED', 'Pinned')");
        }
        jdbc.update("INSERT INTO offers.offer_version (offer_id, version, kind, published_at)"
                + " VALUES ('PINNED', ?, 'fixed-price-phase', now())", version);
        jdbc.update("INSERT INTO offers.offer_version_price (offer_id, version, currency, amount_minor)"
                + " VALUES ('PINNED', ?, 'USD', ?)", version, usdMinorUnits);
    }

    UUID account() {
        UUID account = accounts.open(Currency.getInstance("USD"), ZoneId.of("UTC")).id();
        accounts.recordPaymentToken(account, FakeProvider.token());
        return account;
    }

    List<InvoiceView> paidInvoices(UUID account, int count) {
        await().atMost(Duration.ofSeconds(15)).until(() -> invoicing.invoicesFor(account).stream()
                .filter(i -> i.status() == InvoiceStatus.PAID).count() == count);
        return invoicing.invoicesFor(account);
    }

    @Test
    void anExistingSubscriberRenewsOnTheirVersionAndANewSubscriberGetsTheNewOne() {
        publish(1, 1000);
        UUID existing = account();
        SubscriptionView signedUp = subscriptions.subscribe(existing, "PINNED");
        LocalDate renewal = paidInvoices(existing, 1).getFirst().periodEnd();

        publish(2, 1500);
        Quiesce.untilIdle(jdbc, clock, List.of(existing), Duration.ofSeconds(10));
        clock.set(renewal.atStartOfDay(ZoneId.of("UTC")).toInstant());
        InvoiceView renewed = paidInvoices(existing, 2).get(1);

        UUID newcomer = account();
        SubscriptionView newSignUp = subscriptions.subscribe(newcomer, "PINNED");
        InvoiceView newcomers = paidInvoices(newcomer, 1).getFirst();

        System.out.println("PINNED existing renewal: " + renewed.amount() + " " + renewed.quoteReason().summary());
        System.out.println("PINNED new subscriber:   " + newcomers.amount() + " " + newcomers.quoteReason().summary());
        assertThat(signedUp.offerVersion()).isEqualTo(1);
        assertThat(renewed.periodStart()).isEqualTo(renewal);
        assertThat(renewed.amount()).isEqualTo(Money.of(1000, "USD"));
        assertThat(renewed.offerVersion()).isEqualTo(1);
        assertThat(newSignUp.offerVersion()).isEqualTo(2);
        assertThat(newcomers.amount()).isEqualTo(Money.of(1500, "USD"));
        assertThat(newcomers.offerVersion()).isEqualTo(2);
    }
}
