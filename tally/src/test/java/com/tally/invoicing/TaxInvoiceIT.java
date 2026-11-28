package com.tally.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.account.AccountApi;
import com.tally.platform.TestClock;
import com.tally.shared.Money;
import com.tally.subscription.SubscriptionApi;
import com.tally.support.AbstractProviderIntegrationTest;
import com.tally.support.FakeProvider;
import com.tally.support.TestAccounts;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** A sign-up invoice and its tax lines: the thin path through Invoicing, with the tax adapter settings-backed. */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms",
        "tally.test.context=tax-invoice"
})
class TaxInvoiceIT extends AbstractProviderIntegrationTest {

    @Autowired
    AccountApi accounts;

    @Autowired
    TestAccounts testAccounts;

    @Autowired
    SubscriptionApi subscriptions;

    @Autowired
    InvoicingApi invoicing;

    @AfterEach
    void resetClock() {
        clock.set(TestClock.DEFAULT_START);
    }

    UUID account(String currency, String zone, String country) {
        UUID id = testAccounts.open(Currency.getInstance(currency), ZoneId.of(zone), country).id();
        accounts.recordPaymentToken(id, FakeProvider.token());
        return id;
    }

    InvoiceView awaitPaid(UUID account) {
        await().atMost(Duration.ofSeconds(15)).until(() -> invoicing.invoicesFor(account).stream()
                .anyMatch(i -> i.status() == InvoiceStatus.PAID));
        return invoicing.invoicesFor(account).getFirst();
    }

    @Test
    void aSignUpInTheUaeIsChargedFivePercentVat() {
        UUID account = account("AED", "Asia/Dubai", "AE");

        subscriptions.subscribe(account, "BASIC");

        InvoiceView invoice = awaitPaid(account);
        assertThat(invoicing.invoicesFor(account)).hasSize(1);
        assertThat(FakeProvider.chargeCount(invoice.paymentReference())).isEqualTo(1);
        // 3699 x 5% = 184.95, half up to 185; total 3884.
        assertThat(invoice.amount()).isEqualTo(Money.of(3884, "AED"));
        assertThat(invoice.lines()).hasSize(2);
        assertThat(invoice.lines().get(0).amount()).isEqualTo(Money.of(3699, "AED"));
        assertThat(invoice.lines().get(1).description()).isEqualTo("VAT (5%)");
        assertThat(invoice.lines().get(1).amount()).isEqualTo(Money.of(185, "AED"));
    }

    @Test
    void theVatIsRoundedHalfUpOnEachPlan() {
        UUID account = account("AED", "Asia/Dubai", "AE");

        subscriptions.subscribe(account, "PREMIUM");

        InvoiceView invoice = awaitPaid(account);
        assertThat(invoicing.invoicesFor(account)).hasSize(1);
        assertThat(FakeProvider.chargeCount(invoice.paymentReference())).isEqualTo(1);
        // 10999 x 5% = 549.95, half up to 550; total 11549.
        assertThat(invoice.amount()).isEqualTo(Money.of(11549, "AED"));
        assertThat(invoice.lines()).hasSize(2);
        assertThat(invoice.lines().get(0).amount()).isEqualTo(Money.of(10999, "AED"));
        assertThat(invoice.lines().get(1).description()).isEqualTo("VAT (5%)");
        assertThat(invoice.lines().get(1).amount()).isEqualTo(Money.of(550, "AED"));
    }

    @Test
    void aStandardPlanInTheUaeRoundsTheVatUp() {
        UUID account = account("AED", "Asia/Dubai", "AE");

        subscriptions.subscribe(account, "STANDARD");

        InvoiceView invoice = awaitPaid(account);
        assertThat(invoicing.invoicesFor(account)).hasSize(1);
        assertThat(FakeProvider.chargeCount(invoice.paymentReference())).isEqualTo(1);
        // 7299 x 5% = 364.95, half up to 365; total 7664.
        assertThat(invoice.amount()).isEqualTo(Money.of(7664, "AED"));
        assertThat(invoice.lines()).hasSize(2);
        assertThat(invoice.lines().get(0).amount()).isEqualTo(Money.of(7299, "AED"));
        assertThat(invoice.lines().get(1).description()).isEqualTo("VAT (5%)");
        assertThat(invoice.lines().get(1).amount()).isEqualTo(Money.of(365, "AED"));
    }

    @Test
    void aSignUpInTheUnitedStatesHasNoTaxLine() {
        UUID account = account("USD", "America/New_York", "US");

        subscriptions.subscribe(account, "BASIC");

        InvoiceView invoice = awaitPaid(account);
        assertThat(invoicing.invoicesFor(account)).hasSize(1);
        assertThat(FakeProvider.chargeCount(invoice.paymentReference())).isEqualTo(1);
        // The US is EXEMPT: no tax line, no rounding.
        assertThat(invoice.amount()).isEqualTo(Money.of(999, "USD"));
        assertThat(invoice.lines()).hasSize(1);
        assertThat(invoice.lines().get(0).amount()).isEqualTo(Money.of(999, "USD"));
        assertThat(invoice.lines().stream().map(InvoiceLineView::description))
                .noneMatch(d -> d.startsWith("VAT"));
    }
}
