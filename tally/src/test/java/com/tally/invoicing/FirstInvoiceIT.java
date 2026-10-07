package com.tally.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.account.AccountApi;
import com.tally.payment.FailureReason;
import com.tally.payment.PaymentFailed;
import com.tally.payment.PaymentSucceeded;
import com.tally.shared.Money;
import com.tally.subscription.SubscriptionApi;
import com.tally.subscription.SubscriptionChangeType;
import com.tally.subscription.SubscriptionChanged;
import com.tally.subscription.SubscriptionView;
import com.tally.support.AbstractProviderIntegrationTest;
import com.tally.support.FakeProvider;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** Subscribe, first invoice, collection, invoice paid: the thin path through Invoicing. */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms"
})
class FirstInvoiceIT extends AbstractProviderIntegrationTest {

    @Autowired
    AccountApi accounts;

    @Autowired
    SubscriptionApi subscriptions;

    @Autowired
    InvoicingApi invoicing;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcTemplate jdbc;

    UUID account(String currency, String zone, String token) {
        UUID id = accounts.open(Currency.getInstance(currency), ZoneId.of(zone)).id();
        accounts.recordPaymentToken(id, token);
        return id;
    }

    InvoiceView awaitInvoice(UUID account, InvoiceStatus status) {
        await().atMost(Duration.ofSeconds(10)).until(() -> invoicing.invoicesFor(account).stream()
                .anyMatch(i -> i.status() == status));
        return invoicing.invoicesFor(account).getFirst();
    }

    @Test
    void theFirstInvoiceCoversOneLocalMonthAndIsPaidOnce() {
        // 10:00 UTC on 15 January is still 15 January in New York.
        UUID account = account("USD", "America/New_York", FakeProvider.token());

        SubscriptionView subscription = subscriptions.subscribe(account, "PREMIUM");

        InvoiceView invoice = awaitInvoice(account, InvoiceStatus.PAID);
        assertThat(invoice.subscriptionId()).isEqualTo(subscription.id());
        assertThat(invoice.amount()).isEqualTo(Money.of(2999, "USD"));
        assertThat(invoice.periodStart()).isEqualTo(LocalDate.parse("2026-01-15"));
        assertThat(invoice.periodEnd()).isEqualTo(LocalDate.parse("2026-02-15"));
        assertThat(FakeProvider.chargeCount(invoice.paymentReference())).isEqualTo(1);
    }

    @Test
    void thePeriodStartsOnTheLocalDayInTheAccountsTimeZone() {
        // 10:00 UTC on 15 January is 00:00 on the 16th in Kiritimati (UTC+14).
        UUID account = account("USD", "Pacific/Kiritimati", FakeProvider.token());

        subscriptions.subscribe(account, "BASIC");

        assertThat(awaitInvoice(account, InvoiceStatus.PAID).periodStart()).isEqualTo(LocalDate.parse("2026-01-16"));
    }

    @Test
    void aDeclinedPaymentIsRecordedOnTheInvoice() {
        UUID account = account("AED", "Asia/Dubai", "tok_never_issued");

        subscriptions.subscribe(account, "STANDARD");

        InvoiceView invoice = awaitInvoice(account, InvoiceStatus.PAYMENT_FAILED);
        assertThat(invoice.amount()).isEqualTo(Money.of(7299, "AED"));
    }

    @Test
    void aRedeliveredSubscriptionEventIssuesNoSecondInvoice() {
        UUID account = account("USD", "UTC", FakeProvider.token());
        SubscriptionView subscription = subscriptions.subscribe(account, "BASIC");
        InvoiceView invoice = awaitInvoice(account, InvoiceStatus.PAID);

        // The same fact delivered again, as a resubmission would (ADR-022).
        SubscriptionChanged again = new SubscriptionChanged(UUID.randomUUID(), subscription.startedAt(), account,
                subscription.id(), "BASIC", 1, SubscriptionChangeType.STARTED);
        transactions.executeWithoutResult(tx -> events.publishEvent(again));
        // A late failure for the paid invoice changes nothing either.
        transactions.executeWithoutResult(tx -> events.publishEvent(new PaymentFailed(UUID.randomUUID(),
                clock.instant(), account, invoice.paymentReference(), invoice.amount(), FailureReason.HARD)));

        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).until(() -> {
            List<InvoiceView> all = invoicing.invoicesFor(account);
            return all.size() == 1 && all.getFirst().status() == InvoiceStatus.PAID;
        });
        assertThat(FakeProvider.chargeCount(invoice.paymentReference())).isEqualTo(1);
    }

    @Test
    void aPaymentForAnUnknownReferenceIsIgnored() {
        String reference = "NOT-AN-INVOICE-" + UUID.randomUUID();

        transactions.executeWithoutResult(tx -> events.publishEvent(new PaymentSucceeded(UUID.randomUUID(),
                clock.instant(), UUID.randomUUID(), reference, Money.of(100, "USD"), "ch_x")));

        // The listener completed its publication without error.
        await().atMost(Duration.ofSeconds(5)).until(() -> incompletePublications(reference) == 0
                && publications(reference) > 0);
    }

    int publications(String reference) {
        return jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE serialized_event LIKE ?",
                Integer.class, "%" + reference + "%");
    }

    int incompletePublications(String reference) {
        return jdbc.queryForObject("SELECT count(*) FROM event_publication"
                + " WHERE completion_date IS NULL AND serialized_event LIKE ?", Integer.class, "%" + reference + "%");
    }
}
