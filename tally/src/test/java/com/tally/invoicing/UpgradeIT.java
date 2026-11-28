package com.tally.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * WAR-62: a mid-period upgrade on day 16 of a 30-day period, the unpaid refusal (ADR-042), and a
 * failed charge that leaves the old plan and issues no documents (ADR-041).
 */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms"
})
class UpgradeIT extends AbstractProviderIntegrationTest {

    static final Instant PERIOD_START_INSTANT = Instant.parse("2026-04-01T10:00:00Z");
    static final Instant DAY_16 = Instant.parse("2026-04-16T10:00:00Z");
    static final String GOLDEN_BASIC = "GOLDEN_BASIC";
    static final String GOLDEN_PREMIUM = "GOLDEN_PREMIUM";
    static final String GOLDEN_VIP = "GOLDEN_VIP";
    static final Instant DAY_21 = Instant.parse("2026-04-21T10:00:00Z");
    static final Money BASIC_PRICE = Money.of(1200, "AED");
    static final Money CREDIT = Money.of(600, "AED");
    static final Money CHARGE = Money.of(1000, "AED");
    static final Money NET = Money.of(400, "AED");

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
    void goldenOffersAndAprilClock() {
        clock.set(PERIOD_START_INSTANT);
        insertOffer(GOLDEN_BASIC, "Golden Basic", 1200);
        insertOffer(GOLDEN_PREMIUM, "Golden Premium", 2000);
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
        UUID id = testAccounts.open(Currency.getInstance("AED"), ZoneOffset.UTC).id();
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
    void anUpgradeOnDay16Of30IsChargedTheNetAndThenChangesPlan() {
        UUID account = account(FakeProvider.token());
        SubscriptionView subscription = subscriptions.subscribe(account, GOLDEN_BASIC);
        InvoiceView period = awaitPeriodInvoice(account, InvoiceStatus.PAID);
        assertThat(period.periodStart()).isEqualTo(LocalDate.parse("2026-04-01"));
        assertThat(period.periodEnd()).isEqualTo(LocalDate.parse("2026-05-01"));
        assertThat(period.amount()).isEqualTo(BASIC_PRICE);

        clock.set(DAY_16);
        invoicing.upgrade(subscription.id(), GOLDEN_PREMIUM);

        UUID adjustmentId = Invoice.idOfAdjustment(period.id(), 1);
        await().atMost(Duration.ofSeconds(15)).until(() ->
                invoices.find(adjustmentId).map(i -> i.status() == InvoiceStatus.PAID).orElse(false));

        Invoice adjustment = invoices.find(adjustmentId).orElseThrow();
        assertThat(adjustment.invoiceType()).isEqualTo(InvoiceKind.ADJUSTMENT);
        assertThat(adjustment.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(adjustment.amount()).isEqualTo(CHARGE);
        assertThat(adjustment.planId()).isEqualTo(GOLDEN_PREMIUM);
        assertThat(adjustment.paymentReference()).isEqualTo("INV-" + adjustmentId);

        await().atMost(Duration.ofSeconds(5)).until(() ->
                !credits.ofInvoice(period.id()).isEmpty());
        CreditNote creditNote = credits.ofInvoice(period.id()).getFirst();
        assertThat(creditNote.id()).isEqualTo(CreditNote.idOfUpgradeCredit(period.id(), 1));
        assertThat(creditNote.amount()).isEqualTo(CREDIT);
        assertThat(creditNote.accountId()).isEqualTo(account);
        assertThat(creditNote.invoiceId()).isEqualTo(period.id());
        assertThat(creditNote.appliedToInvoiceId()).isEqualTo(adjustment.id());
        assertThat(adjustment.amount().minus(creditNote.amount())).isEqualTo(NET);

        assertThat(subscriptions.get(subscription.id()).orElseThrow().planId()).isEqualTo(GOLDEN_PREMIUM);
        assertThat(subscriptions.get(subscription.id()).orElseThrow().offerVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT completed_at IS NOT NULL FROM invoicing.upgrade_intent WHERE payment_reference = ?",
                Boolean.class, adjustment.paymentReference())).isTrue();

        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(10));
        assertThat(FakeProvider.chargeCount(adjustment.paymentReference())).isEqualTo(1);
        assertThat(payments.collected(List.of(adjustment.paymentReference())))
                .containsEntry(adjustment.paymentReference(), NET);
    }

    @Test
    void aSecondUpgradeInTheSamePeriodUsesTheNextAdjustmentAndCreditsThePlanBeingLeft() {
        UUID account = account(FakeProvider.token());
        SubscriptionView subscription = subscriptions.subscribe(account, GOLDEN_BASIC);
        InvoiceView period = awaitPeriodInvoice(account, InvoiceStatus.PAID);

        clock.set(DAY_16);
        invoicing.upgrade(subscription.id(), GOLDEN_PREMIUM);
        UUID adjustment1Id = Invoice.idOfAdjustment(period.id(), 1);
        await().atMost(Duration.ofSeconds(15)).until(() ->
                invoices.find(adjustment1Id).map(i -> i.status() == InvoiceStatus.PAID).orElse(false));
        await().atMost(Duration.ofSeconds(5)).until(() ->
                !credits.ofInvoice(period.id()).isEmpty());
        assertThat(subscriptions.get(subscription.id()).orElseThrow().planId()).isEqualTo(GOLDEN_PREMIUM);

        insertOffer(GOLDEN_VIP, "Golden VIP", 3000);
        clock.set(DAY_21);
        invoicing.upgrade(subscription.id(), GOLDEN_VIP);

        UUID adjustment2Id = Invoice.idOfAdjustment(period.id(), 2);
        await().atMost(Duration.ofSeconds(15)).until(() ->
                invoices.find(adjustment2Id).map(i -> i.status() == InvoiceStatus.PAID).orElse(false));
        Invoice adjustment2 = invoices.find(adjustment2Id).orElseThrow();
        assertThat(adjustment2.invoiceType()).isEqualTo(InvoiceKind.ADJUSTMENT);
        assertThat(adjustment2.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(adjustment2.amount()).isEqualTo(Money.of(1000, "AED"));
        assertThat(adjustment2.planId()).isEqualTo(GOLDEN_VIP);
        assertThat(adjustment2.paymentReference()).isEqualTo("INV-" + adjustment2Id);

        Invoice adjustment1 = invoices.find(adjustment1Id).orElseThrow();
        await().atMost(Duration.ofSeconds(5)).until(() ->
                credits.find(CreditNote.idOfUpgradeCredit(period.id(), 2)).isPresent());
        CreditNote credit2 = credits.find(CreditNote.idOfUpgradeCredit(period.id(), 2)).orElseThrow();
        assertThat(credit2.invoiceId()).isEqualTo(adjustment1.id());
        assertThat(credit2.amount()).isEqualTo(Money.of(667, "AED"));
        assertThat(credit2.accountId()).isEqualTo(account);
        assertThat(credit2.appliedToInvoiceId()).isEqualTo(adjustment2.id());
        assertThat(credits.ofInvoice(adjustment1.id()).stream().map(CreditNote::id))
                .containsExactly(credit2.id());
        assertThat(credits.ofInvoice(period.id())).hasSize(1);

        assertThat(subscriptions.get(subscription.id()).orElseThrow().planId()).isEqualTo(GOLDEN_VIP);
        assertThat(subscriptions.get(subscription.id()).orElseThrow().offerVersion()).isEqualTo(1);

        String reference1 = "INV-" + adjustment1Id;
        String reference2 = "INV-" + adjustment2Id;
        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(10));
        assertThat(FakeProvider.chargeCount(reference1)).isEqualTo(1);
        assertThat(FakeProvider.chargeCount(reference2)).isEqualTo(1);
        assertThat(payments.collected(List.of(reference2))).containsEntry(reference2, Money.of(333, "AED"));
    }

    @Test
    void anUnpaidPeriodInvoiceRefusesTheUpgrade() {
        // The period invoice must exist and not be PAID, while the subscription is still ACTIVE
        // (ADR-042). A hard decline can end the subscription through Collections; an outage leaves
        // the invoice pending and the subscription live.
        FakeProvider.mode("OUTAGE");
        UUID account = account(FakeProvider.token());
        SubscriptionView subscription = subscriptions.subscribe(account, GOLDEN_BASIC);
        InvoiceView period = awaitPeriodInvoice(account, InvoiceStatus.PAYMENT_PENDING);
        assertThat(subscriptions.get(subscription.id()).orElseThrow().active()).isTrue();

        clock.set(DAY_16);
        assertThatThrownBy(() -> invoicing.upgrade(subscription.id(), GOLDEN_PREMIUM))
                .isInstanceOf(PeriodInvoiceUnpaidException.class)
                .hasMessageContaining(subscription.id().toString())
                .hasMessageContaining("1");

        assertThat(subscriptions.get(subscription.id()).orElseThrow().planId()).isEqualTo(GOLDEN_BASIC);
        assertThat(credits.ofInvoice(period.id())).isEmpty();
        assertThat(invoices.find(Invoice.idOfAdjustment(period.id(), 1))).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM invoicing.upgrade_intent WHERE period_invoice_id = ?",
                Long.class, period.id())).isZero();
    }

    @Test
    void aFailedUpgradeChargeLeavesTheOldPlanAndIssuesNoDocuments() {
        UUID account = account(FakeProvider.token());
        SubscriptionView subscription = subscriptions.subscribe(account, GOLDEN_BASIC);
        InvoiceView period = awaitPeriodInvoice(account, InvoiceStatus.PAID);

        clock.set(DAY_16);
        FakeProvider.mode("DECLINE_HARD(100)");
        invoicing.upgrade(subscription.id(), GOLDEN_PREMIUM);

        String reference = "INV-" + Invoice.idOfAdjustment(period.id(), 1);
        await().atMost(Duration.ofSeconds(15)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM payment.payment_attempt WHERE reference = ? AND state = 'DECLINED'",
                Long.class, reference) > 0);

        Quiesce.untilIdle(jdbc, clock, List.of(account), Duration.ofSeconds(10));
        assertThat(subscriptions.get(subscription.id()).orElseThrow().planId()).isEqualTo(GOLDEN_BASIC);
        assertThat(credits.ofInvoice(period.id())).isEmpty();
        assertThat(invoices.find(Invoice.idOfAdjustment(period.id(), 1))).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT completed_at IS NOT NULL FROM invoicing.upgrade_intent WHERE payment_reference = ?",
                Boolean.class, reference)).isFalse();
    }
}
