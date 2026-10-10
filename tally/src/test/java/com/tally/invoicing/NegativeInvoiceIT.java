package com.tally.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.AbstractIntegrationTest;
import com.tally.account.AccountApi;
import com.tally.offers.Period;
import com.tally.offers.PriceLine;
import com.tally.offers.domain.OfferTerms;
import com.tally.offers.domain.PricingRule;
import com.tally.shared.Money;
import com.tally.subscription.SubscriptionApi;
import com.tally.subscription.SubscriptionView;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * An offer whose rule credits more than the price would invoice a negative total. Invoice.issue refuses
 * it: no invoice is issued and the delivery fails, so it shows instead of billing the customer a credit.
 * The daily invariant (no negative invoices) stays as a second check.
 */
class NegativeInvoiceIT extends AbstractIntegrationTest {

    /** A faulty rule, test-only: the period's price, then a credit of one and a half times it. */
    static final class OverCreditRule implements PricingRule {

        @Override
        public String kind() {
            return "over-credit";
        }

        @Override
        public List<PriceLine> lines(OfferTerms terms, Money price, Period period) {
            return List.of(PricingRule.periodLine(period, price),
                    new PriceLine("Credit", new Money(-price.minorUnits() * 3 / 2, price.currency())));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FaultyRule {

        @Bean
        OverCreditRule overCreditRule() {
            return new OverCreditRule();
        }
    }

    @Autowired
    AccountApi accounts;

    @Autowired
    SubscriptionApi subscriptions;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void anInvoiceThatWouldBeNegativeIsNotIssued() {
        // Experimental, so other contexts on the shared database start without this rule.
        jdbc.update("INSERT INTO offers.offer_kind (id, status) VALUES ('over-credit', 'EXPERIMENTAL')");
        jdbc.update("INSERT INTO offers.offer (id, name) VALUES ('OVER', 'Over-credited')");
        jdbc.update("INSERT INTO offers.offer_version (offer_id, version, kind, published_at)"
                + " VALUES ('OVER', 1, 'over-credit', now())");
        jdbc.update("INSERT INTO offers.offer_version_price (offer_id, version, currency, amount_minor)"
                + " VALUES ('OVER', 1, 'USD', 1000)");
        UUID account = accounts.open(Currency.getInstance("USD"), ZoneId.of("UTC")).id();

        SubscriptionView subscription = subscriptions.subscribe(account, "OVER");

        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("SELECT count(*) FROM event_publication"
                + " WHERE serialized_event LIKE ? AND status = 'FAILED'", Long.class, "%" + subscription.id() + "%") == 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoicing.invoice WHERE subscription_id = ?", Long.class,
                subscription.id())).isZero();
    }
}
