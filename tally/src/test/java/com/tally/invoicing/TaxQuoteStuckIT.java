package com.tally.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.account.AccountApi;
import com.tally.invoicing.domain.TaxRates;
import com.tally.invoicing.domain.TaxQuote;
import com.tally.invoicing.domain.TaxUnavailableException;
import com.tally.platform.TestClock;
import com.tally.shared.Ratio;
import com.tally.subscription.SubscriptionApi;
import com.tally.support.AbstractProviderIntegrationTest;
import com.tally.support.FakeProvider;
import com.tally.support.TestAccounts;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * On an outage of the external tax service, the invoice waits DRAFT on the tax-quote queue and retries
 * (ADR-050). The retry moves the due time forward, so the due-age gauge sees nothing; the retrying gauge,
 * which counts from creation, still sees it (ADR-052). Own context: it moves the clock.
 */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms",
        "tally.tax.retry-delay=2s",
        "tally.test.context=tax-quote-stuck"
})
class TaxQuoteStuckIT extends AbstractProviderIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class SwitchableTax {

        static final AtomicBoolean DOWN = new AtomicBoolean(false);
        static final AtomicInteger CALLS = new AtomicInteger();

        @Bean
        @Primary
        TaxRates switchableTaxRates() {
            return request -> {
                CALLS.incrementAndGet();
                if (DOWN.get()) {
                    throw new TaxUnavailableException("tax is down");
                }
                return new TaxQuote(Ratio.of(5, 100), "VAT (5%)");
            };
        }
    }

    @Autowired
    AccountApi accounts;

    @Autowired
    TestAccounts testAccounts;

    @Autowired
    SubscriptionApi subscriptions;

    @Autowired
    InvoicingApi invoicing;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MeterRegistry meters;

    @BeforeEach
    void up() {
        SwitchableTax.DOWN.set(false);
        SwitchableTax.CALLS.set(0);
    }

    @AfterEach
    void down() {
        SwitchableTax.DOWN.set(false);
        clock.set(TestClock.DEFAULT_START);
    }

    double gauge(String name) {
        return meters.get(name).tag("queue", "tax-quote").gauge().value();
    }

    String queueStatus(UUID invoice) {
        return jdbc.queryForObject("SELECT status FROM invoicing.tax_quote_queue WHERE queue_key = ?",
                String.class, invoice.toString());
    }

    @Test
    void aTaxOutageIsSeenByTheRetryingGaugeButNotByTheDueGauge() {
        SwitchableTax.DOWN.set(true);
        UUID account = testAccounts.open(Currency.getInstance("AED"), ZoneId.of("Asia/Dubai"), "AE").id();
        accounts.recordPaymentToken(account, FakeProvider.token());
        subscriptions.subscribe(account, "BASIC");

        await().atMost(Duration.ofSeconds(10)).until(() -> {
            List<InvoiceView> invoices = invoicing.invoicesFor(account);
            if (invoices.size() != 1) {
                return false;
            }
            InvoiceView draft = invoices.getFirst();
            return draft.status() == InvoiceStatus.DRAFT
                    && jdbc.queryForObject("SELECT count(*) FROM invoicing.tax_quote_queue WHERE queue_key = ?",
                            Integer.class, draft.id().toString()) == 1;
        });
        InvoiceView draft = invoicing.invoicesFor(account).getFirst();

        // Advance the clock so the first retry is due, then wait for it to complete.
        clock.advance(Duration.ofSeconds(3));
        await().atMost(Duration.ofSeconds(15)).until(() ->
                jdbc.queryForObject("SELECT attempts FROM invoicing.tax_quote_queue WHERE queue_key = ?",
                        Integer.class, draft.id().toString()) >= 1
                        && queueStatus(draft.id()).equals("READY"));

        // Two hours of outage: the retry runs again and the invoice is still DRAFT.
        int callsBefore = SwitchableTax.CALLS.get();
        clock.advance(Duration.ofHours(2));
        await().atMost(Duration.ofSeconds(15)).until(() -> {
            List<InvoiceView> invoices = invoicing.invoicesFor(account);
            return SwitchableTax.CALLS.get() > callsBefore
                    && invoices.size() == 1
                    && invoices.getFirst().status() == InvoiceStatus.DRAFT
                    && queueStatus(draft.id()).equals("READY");
        });

        // The retrying gauge counts from creation and keeps growing; the due gauge does not.
        assertThat(gauge("tally.queue.oldest_retrying_age_seconds")).isGreaterThanOrEqualTo(7200.0);
        assertThat(gauge("tally.queue.oldest_due_age_seconds")).isLessThan(60.0);
        assertThat(meters.get("tally.queue.oldest_retrying_age_seconds")
                .tags("queue", "tax-quote", "money", "true").gauge()).isNotNull();

        // The service comes back; the draft is issued with tax.
        SwitchableTax.DOWN.set(false);
        clock.advance(Duration.ofSeconds(3));
        await().atMost(Duration.ofSeconds(15)).until(() -> {
            List<InvoiceView> invoices = invoicing.invoicesFor(account);
            return !invoices.isEmpty() && invoices.getFirst().status() == InvoiceStatus.PAID
                    && queueStatus(draft.id()).equals("DONE");
        });
        assertThat(gauge("tally.queue.oldest_retrying_age_seconds")).isZero();
    }
}
