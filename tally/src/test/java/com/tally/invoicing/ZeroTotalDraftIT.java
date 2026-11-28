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
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * A zero-total draft that the tax-quote worker issues publishes InvoiceIssued and InvoicePaid as two
 * versions (ADR-016): the issue writes version 1, then paid(now) writes version 2. On the way out of the
 * gap it was waiting in, the two events carry both versions.
 */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms",
        "tally.tax.retry-delay=2s",
        "tally.test.context=zero-total-draft"
})
class ZeroTotalDraftIT extends AbstractProviderIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class SwitchableTax {

        static final AtomicBoolean DOWN = new AtomicBoolean(false);
        static final List<InvoiceIssued> ISSUED = new CopyOnWriteArrayList<>();
        static final List<InvoicePaid> PAID = new CopyOnWriteArrayList<>();

        @Bean
        @Primary
        TaxRates switchableTaxRates() {
            return request -> {
                if (DOWN.get()) {
                    throw new TaxUnavailableException("tax is down");
                }
                return new TaxQuote(Ratio.of(5, 100), "VAT (5%)");
            };
        }

        @EventListener
        void on(InvoiceIssued issued) {
            ISSUED.add(issued);
        }

        @EventListener
        void on(InvoicePaid paid) {
            PAID.add(paid);
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

    @AfterEach
    void cleanup() {
        SwitchableTax.DOWN.set(false);
        jdbc.update("UPDATE offers.offer_kind SET status = 'EXPERIMENTAL' WHERE id = 'free-trial'");
        clock.set(TestClock.DEFAULT_START);
    }

    @Test
    void aZeroTotalDraftIssuedAfterAnOutagePublishesTwoSequences() {
        // Tax is down when the subscription is created, so the free trial's invoice waits DRAFT.
        SwitchableTax.DOWN.set(true);
        jdbc.update("UPDATE offers.offer_kind SET status = 'APPROVED' WHERE id = 'free-trial'");
        jdbc.update("INSERT INTO offers.offer (id, name) VALUES ('TRIAL', 'One month free')");
        var published = clock.instant().atOffset(ZoneOffset.UTC);
        jdbc.update("INSERT INTO offers.offer_version (offer_id, version, kind, free_periods, published_at, effective_from)"
                + " VALUES ('TRIAL', 1, 'free-trial', 1, ?, ?)", published, published.toLocalDate());
        jdbc.update("INSERT INTO offers.offer_version_price (offer_id, version, currency, amount_minor)"
                + " VALUES ('TRIAL', 1, 'AED', 3699)");
        UUID account = testAccounts.open(Currency.getInstance("AED"), ZoneId.of("Asia/Dubai"), "AE").id();
        accounts.recordPaymentToken(account, FakeProvider.token());

        subscriptions.subscribe(account, "TRIAL");

        await().atMost(Duration.ofSeconds(10)).until(() -> !invoicing.invoicesFor(account).isEmpty());
        assertThat(invoicing.invoicesFor(account).getFirst().status()).isEqualTo(InvoiceStatus.DRAFT);

        // The service comes back; the draft is issued and paid.
        SwitchableTax.DOWN.set(false);
        clock.advance(Duration.ofSeconds(3));
        await().atMost(Duration.ofSeconds(15)).until(() -> {
            List<InvoiceView> invoices = invoicing.invoicesFor(account);
            return !invoices.isEmpty() && invoices.getFirst().status() == InvoiceStatus.PAID;
        });
        InvoiceView issued = invoicing.invoicesFor(account).getFirst();

        List<InvoiceIssued> issuedEvents = SwitchableTax.ISSUED.stream()
                .filter(event -> event.invoiceId().equals(issued.id())).toList();
        assertThat(issuedEvents).as("InvoiceIssued").hasSize(1);
        assertThat(issuedEvents.getFirst().sequence()).isEqualTo(1L);

        List<InvoicePaid> paidEvents = SwitchableTax.PAID.stream()
                .filter(event -> event.invoiceId().equals(issued.id())).toList();
        assertThat(paidEvents).as("InvoicePaid").hasSize(1);
        assertThat(paidEvents.getFirst().sequence()).isEqualTo(2L);

        assertThat(jdbc.queryForObject("SELECT version FROM invoicing.invoice WHERE id = ?", Long.class,
                issued.id())).as("version").isEqualTo(2L);
        assertThat(issued.amount()).isEqualTo(Money.of(0, "AED"));
        assertThat(FakeProvider.requestCount(issued.paymentReference())).isZero();
    }
}
