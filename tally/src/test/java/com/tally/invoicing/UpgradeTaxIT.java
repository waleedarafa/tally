package com.tally.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.account.AccountApi;
import com.tally.invoicing.domain.CreditNote;
import com.tally.invoicing.domain.CreditNotes;
import com.tally.invoicing.domain.Invoice;
import com.tally.invoicing.domain.InvoiceKind;
import com.tally.invoicing.domain.Invoices;
import com.tally.payment.PaymentApi;
import com.tally.platform.TestClock;
import com.tally.shared.Money;
import com.tally.subscription.SubscriptionApi;
import com.tally.subscription.SubscriptionView;
import com.tally.support.AbstractProviderIntegrationTest;
import com.tally.support.FakeProvider;
import com.tally.support.Quiesce;
import com.tally.support.TestAccounts;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * A mid-period upgrade in the UAE: the adjustment and the credit both carry VAT (ADR-051), and the
 * charge is the net of the two documents, VAT included.
 */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms",
        "tally.test.context=upgrade-tax"
})
class UpgradeTaxIT extends AbstractProviderIntegrationTest {

    static final Instant PERIOD_START_INSTANT = Instant.parse("2026-04-01T10:00:00Z");
    static final Instant DAY_16 = Instant.parse("2026-04-16T10:00:00Z");
    static final String TAXUP_BASIC = "TAXUP_BASIC";
    static final String TAXUP_PREMIUM = "TAXUP_PREMIUM";
    static final Money BASIC_PRICE = Money.of(1260, "AED");

    @Autowired
    AccountApi accounts;

    @Autowired
    TestAccounts testAccounts;

    @Autowired
    SubscriptionApi subscriptions;

    @Autowired
    InvoicingApi invoicing;

    @Autowired
    Invoices invoices;

    @Autowired
    CreditNotes credits;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PaymentApi payments;

    @BeforeEach
    void taxOffersAndAprilClock() {
        clock.set(PERIOD_START_INSTANT);
        insertOffer(TAXUP_BASIC, "Tax Basic", 1200);
        insertOffer(TAXUP_PREMIUM, "Tax Premium", 2000);
    }

    @AfterEach
    void resetClock() {
        clock.set(TestClock.DEFAULT_START);
    }

    void insertOffer(String id, String name, long amountMinor) {
        jdbc.update("INSERT INTO offers.offer (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING", id, name);
        jdbc.update("""
                INSERT INTO offers.offer_version (offer_id, version, kind, published_at, effective_from)
                VALUES (?, 1, 'fixed-price-phase', TIMESTAMPTZ '2026-01-01 00:00:00+00', DATE '2026-01-01')
                ON CONFLICT (offer_id, version) DO NOTHING
                """, id);
        jdbc.update("""
                INSERT INTO offers.offer_version_price (offer_id, version, currency, amount_minor)
                VALUES (?, 1, 'AED', ?)
                ON CONFLICT (offer_id, version, currency) DO NOTHING
                """, id, amountMinor);
    }

    UUID account(String token) {
        UUID id = testAccounts.open(Currency.getInstance("AED"), ZoneOffset.UTC, "AE").id();
        accounts.recordPaymentToken(id, token);
        return id;
    }

    InvoiceView awaitPeriodInvoice(UUID account, InvoiceStatus status) {
        await().atMost(Duration.ofSeconds(15)).until(() -> invoicing.invoicesFor(account).stream()
                .anyMatch(i -> i.status() == status && i.amount().equals(BASIC_PRICE)));
        return invoicing.invoicesFor(account).stream()
                .filter(i -> i.amount().equals(BASIC_PRICE))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void anUpgradeInTheUaeCarriesVatOnTheAdjustmentAndTheCredit() {
        UUID account = account(FakeProvider.token());
        SubscriptionView subscription = subscriptions.subscribe(account, TAXUP_BASIC);
        InvoiceView period = awaitPeriodInvoice(account, InvoiceStatus.PAID);
        assertThat(period.periodStart()).isEqualTo(LocalDate.parse("2026-04-01"));
        assertThat(period.amount()).isEqualTo(Money.of(1260, "AED"));
        assertThat(period.lines()).hasSize(2);
        assertThat(period.lines().get(0).amount()).isEqualTo(Money.of(1200, "AED"));
        assertThat(period.lines().get(1).description()).isEqualTo("VAT (5%)");
        assertThat(period.lines().get(1).amount()).isEqualTo(Money.of(60, "AED"));

        clock.set(DAY_16);
        invoicing.upgrade(subscription.id(), TAXUP_PREMIUM);

        UUID adjustmentId = Invoice.idOfAdjustment(period.id(), 1);
        await().atMost(Duration.ofSeconds(15)).until(() ->
                invoices.find(adjustmentId).map(i -> i.status() == InvoiceStatus.PAID).orElse(false));

        Invoice adjustment = invoices.find(adjustmentId).orElseThrow();
        assertThat(adjustment.invoiceType()).isEqualTo(InvoiceKind.ADJUSTMENT);
        assertThat(adjustment.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(adjustment.amount()).isEqualTo(Money.of(1050, "AED"));
        assertThat(adjustment.lines()).hasSize(2);
        assertThat(adjustment.lines().get(0).amount()).isEqualTo(Money.of(1000, "AED"));
        assertThat(adjustment.lines().get(1).description()).isEqualTo("VAT (5%)");
        assertThat(adjustment.lines().get(1).amount()).isEqualTo(Money.of(50, "AED"));
        assertThat(adjustment.planId()).isEqualTo(TAXUP_PREMIUM);

        await().atMost(Duration.ofSeconds(5)).until(() ->
                !credits.ofInvoice(period.id()).isEmpty());
        CreditNote creditNote = credits.ofInvoice(period.id()).getFirst();
        assertThat(creditNote.id()).isEqualTo(CreditNote.idOfUpgradeCredit(period.id(), 1));
        assertThat(creditNote.amount()).isEqualTo(Money.of(630, "AED"));
        assertThat(creditNote.lines()).hasSize(2);
        assertThat(creditNote.lines().get(0).amount()).isEqualTo(Money.of(600, "AED"));
        assertThat(creditNote.lines().get(1).description()).isEqualTo("VAT (5%)");
        assertThat(creditNote.lines().get(1).amount()).isEqualTo(Money.of(30, "AED"));
        assertThat(creditNote.invoiceId()).isEqualTo(period.id());
        assertThat(creditNote.appliedToInvoiceId()).isEqualTo(adjustment.id());

        assertThat(subscriptions.get(subscription.id()).orElseThrow().planId()).isEqualTo(TAXUP_PREMIUM);

        String reference = adjustment.paymentReference();
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(10));
        assertThat(FakeProvider.chargeCount(reference)).isEqualTo(1);
        assertThat(payments.collected(List.of(reference)))
                .containsEntry(reference, Money.of(420, "AED"));
    }
}
