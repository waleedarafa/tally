package com.tally.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.account.AccountApi;
import com.tally.invoicing.domain.TaxRates;
import com.tally.invoicing.domain.TaxQuote;
import com.tally.invoicing.domain.TaxUnavailableException;
import com.tally.platform.TestClock;
import com.tally.shared.Money;
import com.tally.shared.Ratio;
import com.tally.subscription.SubscriptionApi;
import com.tally.support.AbstractProviderIntegrationTest;
import com.tally.support.FakeProvider;
import com.tally.support.TestAccounts;
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
 * On an outage of the external tax service, the invoice waits DRAFT on the tax-quote queue, retries
 * (ADR-050), and is issued with tax as soon as the service is back. Own context: it moves the clock.
 */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms",
        "tally.tax.retry-delay=2s",
        "tally.test.context=tax-outage"
})
class TaxOutageIT extends AbstractProviderIntegrationTest {

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

    int queueCount(UUID invoice, String status) {
        return jdbc.queryForObject("SELECT count(*) FROM invoicing.tax_quote_queue WHERE queue_key = ? AND status = ?",
                Integer.class, invoice.toString(), status);
    }

    @Test
    void anInvoiceWaitsDraftWhileTaxIsDownThenIsIssuedWithTax() {
        SwitchableTax.DOWN.set(true);
        UUID account = testAccounts.open(Currency.getInstance("AED"), ZoneId.of("Asia/Dubai"), "AE").id();
        accounts.recordPaymentToken(account, FakeProvider.token());
        subscriptions.subscribe(account, "BASIC");

        await().atMost(Duration.ofSeconds(10)).until(() -> !invoicing.invoicesFor(account).isEmpty());
        InvoiceView draft = invoicing.invoicesFor(account).getFirst();
        String originalDescription = draft.lines().getFirst().description();
        assertThat(draft.status()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(draft.issuedAt()).isNull();
        assertThat(draft.amount()).isEqualTo(Money.of(3699, "AED"));
        assertThat(draft.lines()).hasSize(1);
        assertThat(invoicing.invoicesFor(account)).hasSize(1);
        assertThat(queueCount(draft.id(), "READY")).isEqualTo(1);

        // Retries happen and still issue nothing.
        int callsBefore = SwitchableTax.CALLS.get();
        clock.advance(Duration.ofSeconds(3));
        await().atMost(Duration.ofSeconds(10)).until(() -> SwitchableTax.CALLS.get() > callsBefore
                && queueCount(draft.id(), "READY") == 1);
        InvoiceView stillDrafted = invoicing.invoicesFor(account).getFirst();
        assertThat(stillDrafted.status()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(stillDrafted.issuedAt()).isNull();
        assertThat(queueCount(draft.id(), "READY")).isEqualTo(1);
        assertThat(FakeProvider.chargeCount(stillDrafted.paymentReference())).isZero();

        // The service comes back; the draft is issued with tax.
        SwitchableTax.DOWN.set(false);
        clock.advance(Duration.ofSeconds(3));
        await().atMost(Duration.ofSeconds(15)).until(() -> {
            List<InvoiceView> invoices = invoicing.invoicesFor(account);
            return !invoices.isEmpty() && invoices.getFirst().status() != InvoiceStatus.DRAFT;
        });
        InvoiceView issued = invoicing.invoicesFor(account).getFirst();
        assertThat(issued.issuedAt()).isNotNull();
        assertThat(issued.amount()).isEqualTo(Money.of(3884, "AED"));
        assertThat(issued.lines()).hasSize(2);
        assertThat(issued.lines().get(0).amount()).isEqualTo(Money.of(3699, "AED"));
        assertThat(issued.lines().get(0).description()).isEqualTo(originalDescription);
        assertThat(issued.lines().get(1).description()).isEqualTo("VAT (5%)");
        assertThat(issued.lines().get(1).amount()).isEqualTo(Money.of(185, "AED"));
        assertThat(invoicing.invoicesFor(account)).hasSize(1);

        await().atMost(Duration.ofSeconds(15)).until(() -> {
            List<InvoiceView> invoices = invoicing.invoicesFor(account);
            return !invoices.isEmpty() && invoices.getFirst().status() == InvoiceStatus.PAID
                    && queueCount(issued.id(), "DONE") == 1;
        });
        assertThat(FakeProvider.chargeCount(issued.paymentReference())).isEqualTo(1);
        assertThat(queueCount(issued.id(), "DONE")).isEqualTo(1);
    }
}
