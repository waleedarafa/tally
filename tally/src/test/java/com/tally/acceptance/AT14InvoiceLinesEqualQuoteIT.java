package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.account.AccountApi;
import com.tally.invoicing.InvoiceLineView;
import com.tally.invoicing.InvoiceView;
import com.tally.invoicing.InvoicingApi;
import com.tally.invoicing.PricingReason;
import com.tally.offers.Period;
import com.tally.offers.PriceQuote;
import com.tally.offers.Pricing;
import com.tally.offers.QuoteRequest;
import com.tally.subscription.SubscriptionApi;
import com.tally.subscription.SubscriptionView;
import com.tally.support.AbstractProviderIntegrationTest;
import com.tally.support.FakeProvider;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * AT-14. Invoice lines equal the PriceQuote (I3, R2, D7). Pass when: the invoice's lines and total
 * match the quote for the same subscription, period and offer version, and the invoice's reason names
 * the offer version.
 */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms"
})
class AT14InvoiceLinesEqualQuoteIT extends AbstractProviderIntegrationTest {

    @Autowired
    AccountApi accounts;

    @Autowired
    SubscriptionApi subscriptions;

    @Autowired
    InvoicingApi invoicing;

    @Autowired
    Pricing pricing;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theInvoiceCarriesTheQuotesLinesTotalAndOfferVersion() {
        UUID account = accounts.open(Currency.getInstance("AED"), ZoneId.of("Asia/Dubai")).id();
        accounts.recordPaymentToken(account, FakeProvider.token());
        SubscriptionView subscription = subscriptions.subscribe(account, "STANDARD");
        await().atMost(Duration.ofSeconds(10)).until(() -> !invoicing.invoicesFor(account).isEmpty());
        InvoiceView invoice = invoicing.invoicesFor(account).getFirst();

        PriceQuote quote = pricing.quote(new QuoteRequest(subscription.id(), account, "STANDARD", subscription.offerVersion(),
                new Period(1, invoice.periodStart(), invoice.periodEnd())));

        System.out.println("AT-14 quote   " + quote);
        System.out.println("AT-14 invoice lines=" + invoice.lines() + " amount=" + invoice.amount()
                + " offerVersion=" + invoice.offerVersion() + " reason=" + invoice.quoteReason());
        assertThat(invoice.lines()).containsExactlyElementsOf(quote.lines().stream()
                .map(line -> new InvoiceLineView(line.description(), line.amount())).toList());
        assertThat(invoice.amount()).isEqualTo(quote.total());
        assertThat(invoice.offerVersion()).isEqualTo(quote.offerVersion()).isEqualTo(1);
        assertThat(invoice.quoteReason()).isEqualTo(new PricingReason(quote.reason().offerId(),
                quote.reason().offerVersion(), quote.reason().kind(), quote.reason().flags()));
        assertThat(invoice.quoteReason().summary()).isEqualTo(quote.reason().summary())
                .contains("STANDARD").contains("version 1");

        // Stored as data, not text, so ADR-010's flag report can query it.
        String stored = jdbc.queryForObject("SELECT jsonb_build_object('offerId', quote_reason->>'offerId',"
                        + " 'version', (quote_reason->>'offerVersion')::int, 'kind', quote_reason->>'kind',"
                        + " 'noFlags', quote_reason->'flags' = '{}'::jsonb)::text"
                        + " FROM invoicing.invoice WHERE id = ?", String.class, invoice.id());
        System.out.println("AT-14 stored reason " + stored);
        assertThat(stored).isEqualTo(
                "{\"kind\": \"fixed-price-phase\", \"noFlags\": true, \"offerId\": \"STANDARD\", \"version\": 1}");
    }
}
