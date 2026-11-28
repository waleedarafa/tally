package com.tally.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.account.AccountApi;
import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.InvoiceView;
import com.tally.invoicing.InvoicingApi;
import com.tally.offers.Offers;
import com.tally.offers.Period;
import com.tally.offers.PriceQuote;
import com.tally.offers.Pricing;
import com.tally.offers.QuoteRequest;
import com.tally.platform.TestClock;
import com.tally.shared.Money;
import com.tally.support.AbstractProviderIntegrationTest;
import com.tally.support.FakeProvider;
import com.tally.support.TestAccounts;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * ADR-047 (WAR-65, A-06): a version published on 20 October may take effect on 1 November. A sign-up
 * before that date pins v1; after the clock is advanced a new sign-up gets v2; the first subscription
 * is still quoted at v1. Own context: it moves the clock.
 */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms",
        "tally.test.context=offer-version-effective-date"
})
class OfferVersionEffectiveDateIT extends AbstractProviderIntegrationTest {

    @Autowired
    AccountApi accounts;

    @Autowired
    TestAccounts testAccounts;

    @Autowired
    SubscriptionApi subscriptions;

    @Autowired
    InvoicingApi invoicing;

    @Autowired
    Pricing pricing;

    @Autowired
    Offers offers;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void resetClock() {
        clock.set(TestClock.DEFAULT_START);
    }

    void publish(int version, Instant publishedAt, LocalDate effectiveFrom, long usdMinorUnits) {
        if (version == 1) {
            jdbc.update("INSERT INTO offers.offer (id, name) VALUES ('DATED', 'Dated')");
        }
        jdbc.update("INSERT INTO offers.offer_version (offer_id, version, kind, published_at, effective_from)"
                        + " VALUES ('DATED', ?, 'fixed-price-phase', ?, ?)",
                version, publishedAt.atOffset(ZoneOffset.UTC), effectiveFrom);
        jdbc.update("INSERT INTO offers.offer_version_price (offer_id, version, currency, amount_minor)"
                + " VALUES ('DATED', ?, 'USD', ?)", version, usdMinorUnits);
    }

    UUID account() {
        UUID account = testAccounts.open(Currency.getInstance("USD"), ZoneId.of("UTC")).id();
        accounts.recordPaymentToken(account, FakeProvider.token());
        return account;
    }

    InvoiceView paidInvoice(UUID account) {
        await().atMost(Duration.ofSeconds(15)).until(() -> invoicing.invoicesFor(account).stream()
                .anyMatch(i -> i.status() == InvoiceStatus.PAID));
        return invoicing.invoicesFor(account).getFirst();
    }

    @Test
    void aSignUpBeforeTheEffectiveDatePinsV1AndALaterSignUpGetsV2() {
        clock.set(Instant.parse("2026-10-20T10:00:00Z"));
        publish(1, Instant.parse("2026-01-01T00:00:00Z"), LocalDate.parse("2026-01-01"), 999);
        publish(2, Instant.parse("2026-10-20T10:00:00Z"), LocalDate.parse("2026-11-01"), 1099);

        UUID existing = account();
        SubscriptionView first = subscriptions.subscribe(existing, "DATED");
        InvoiceView firstInvoice = paidInvoice(existing);

        clock.set(Instant.parse("2026-11-01T00:00:00Z"));
        PriceQuote stillQuoted = pricing.quote(new QuoteRequest(first.id(), first.accountId(), first.planId(),
                first.offerVersion(), new Period(1, firstInvoice.periodStart(), firstInvoice.periodEnd())));
        UUID newcomer = account();
        SubscriptionView second = subscriptions.subscribe(newcomer, "DATED");
        InvoiceView secondInvoice = paidInvoice(newcomer);

        System.out.println("EFFECTIVE first:  " + first.offerVersion() + " " + firstInvoice.amount());
        System.out.println("EFFECTIVE quoted: " + stillQuoted.offerVersion() + " " + stillQuoted.total());
        System.out.println("EFFECTIVE second: " + second.offerVersion() + " " + secondInvoice.amount());
        assertThat(first.offerVersion()).isEqualTo(1);
        assertThat(firstInvoice.offerVersion()).isEqualTo(1);
        assertThat(firstInvoice.amount()).isEqualTo(Money.of(999, "USD"));
        assertThat(stillQuoted.offerVersion()).isEqualTo(1);
        assertThat(stillQuoted.total()).isEqualTo(Money.of(999, "USD"));
        assertThat(subscriptions.get(first.id()).orElseThrow().offerVersion()).isEqualTo(1);
        assertThat(offers.currentVersion("DATED", existing)).isEqualTo(OptionalInt.of(2));
        assertThat(second.offerVersion()).isEqualTo(2);
        assertThat(secondInvoice.offerVersion()).isEqualTo(2);
        assertThat(secondInvoice.amount()).isEqualTo(Money.of(1099, "USD"));
    }
}
