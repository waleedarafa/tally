package com.tally.invoicing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tally.account.AccountApi;
import com.tally.account.AccountView;
import com.tally.account.Standing;
import com.tally.invoicing.PeriodInvoiceUnpaidException;
import com.tally.invoicing.PricingReason;
import com.tally.invoicing.UpgradeAlreadyInProgressException;
import com.tally.invoicing.domain.CreditNote;
import com.tally.invoicing.domain.CreditNotes;
import com.tally.invoicing.domain.Invoice;
import com.tally.invoicing.domain.InvoiceLine;
import com.tally.invoicing.domain.Invoices;
import com.tally.invoicing.domain.UpgradeIntent;
import com.tally.invoicing.domain.UpgradeIntents;
import com.tally.offers.ChangeRequest;
import com.tally.offers.Offers;
import com.tally.offers.Period;
import com.tally.offers.PriceLine;
import com.tally.offers.PriceQuote;
import com.tally.offers.Pricing;
import com.tally.offers.QuoteReason;
import com.tally.offers.QuoteRequest;
import com.tally.payment.PaymentApi;
import com.tally.payment.PaymentStatus;
import com.tally.shared.Money;
import com.tally.subscription.EndReason;
import com.tally.subscription.SubscriptionApi;
import com.tally.subscription.SubscriptionSnapshot;
import com.tally.subscription.SubscriptionView;
import com.tally.subscription.TimelineEntry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.Test;

class UpgradeCommandTest {

    static final Instant NOW = Instant.parse("2026-01-16T10:00:00Z");
    static final int PERIOD = 1;
    static final LocalDate PERIOD_START = LocalDate.parse("2026-01-01");
    static final LocalDate PERIOD_END = LocalDate.parse("2026-02-01");
    static final LocalDate EFFECTIVE_DATE = LocalDate.of(2026, 1, 16);
    static final Money GOLDEN_NET = Money.of(400, "AED");

    // --- Tiny doubles ---

    static final class StubSubscriptionApi implements SubscriptionApi {
        private final Optional<SubscriptionSnapshot> subscription;

        StubSubscriptionApi(Optional<SubscriptionSnapshot> subscription) { this.subscription = subscription; }

        @Override
        public Optional<SubscriptionSnapshot> get(UUID subscriptionId) { return subscription; }

        @Override public SubscriptionView subscribe(UUID accountId, String planId) { throw new UnsupportedOperationException(); }
        @Override public SubscriptionView subscribe(UUID accountId, String planId, UUID signUpKey) { throw new UnsupportedOperationException(); }
        @Override public SubscriptionView startTrial(UUID accountId, String planId) { throw new UnsupportedOperationException(); }
        @Override public boolean endTrial(UUID subscriptionId) { throw new UnsupportedOperationException(); }
        @Override public boolean cancel(UUID subscriptionId) { throw new UnsupportedOperationException(); }
        @Override public boolean periodEnded(UUID subscriptionId) { throw new UnsupportedOperationException(); }
        @Override public List<UUID> endLiveSubscriptions(UUID accountId, EndReason reason) { throw new UnsupportedOperationException(); }
        @Override public boolean end(UUID subscriptionId, EndReason reason) { throw new UnsupportedOperationException(); }
        @Override public List<TimelineEntry> timeline(UUID subscriptionId) { throw new UnsupportedOperationException(); }
        @Override public boolean changePlan(UUID subscriptionId, String planId, int offerVersion) { throw new UnsupportedOperationException(); }
    }

    static final class FakeInvoices implements Invoices {
        private final Map<UUID, Invoice> byId = new LinkedHashMap<>();
        int addIfAbsentCalls = 0;

        void add(Invoice invoice) { byId.put(invoice.id(), invoice); }

        @Override public boolean addIfAbsent(Invoice invoice) {
            addIfAbsentCalls++;
            if (byId.containsKey(invoice.id())) { return false; }
            byId.put(invoice.id(), invoice);
            return true;
        }

        @Override public Optional<Invoice> find(UUID invoiceId) { return Optional.ofNullable(byId.get(invoiceId)); }

        @Override public Optional<Invoice> forPaymentReference(String paymentReference) { return Optional.empty(); }

        @Override public void update(Invoice invoice) {}

        @Override public List<Invoice> ofAccount(UUID accountId) { return List.of(); }

        @Override public List<Invoice> ofSubscription(UUID subscriptionId) { return List.of(); }

        @Override public List<Invoice> issuedBetween(Instant from, Instant to) { return List.of(); }

        @Override public List<Invoice> adjustmentsOf(UUID periodInvoiceId) {
            return byId.values().stream()
                    .filter(i -> periodInvoiceId.equals(i.periodInvoiceId()))
                    .sorted(Comparator.comparing(Invoice::issuedAt))
                    .toList();
        }
    }

    static final class FakeAccounts implements AccountApi {
        private final UUID accountId;
        private final Optional<String> token;

        FakeAccounts(UUID accountId, Optional<String> token) { this.accountId = accountId; this.token = token; }

        @Override
        public Optional<AccountView> get(UUID accountId) {
            return Optional.of(new AccountView(accountId, Currency.getInstance("AED"), ZoneId.of("UTC"), token, Standing.GOOD, OptionalInt.empty()));
        }

        @Override public void recordPaymentToken(UUID accountId, String paymentToken) {}
    }

    static final class FakeOffers implements Offers {
        private final OptionalInt version;

        FakeOffers(OptionalInt version) { this.version = version; }

        @Override public OptionalInt currentVersion(String offerId, UUID accountId) { return version; }
    }

    static final class FakePricing implements Pricing {
        private final PriceQuote quote;
        ChangeRequest lastChangeRequest;

        FakePricing(PriceQuote quote) { this.quote = quote; }

        @Override public PriceQuote quote(QuoteRequest request) { throw new UnsupportedOperationException(); }

        @Override
        public PriceQuote quoteChange(ChangeRequest request) { this.lastChangeRequest = request; return quote; }
    }

    static final class FakePayments implements PaymentApi {
        record Call(String reference, UUID accountId, Money amount, String token) {}

        final List<Call> calls = new ArrayList<>();

        @Override
        public void requestCollection(String reference, UUID accountId, Money amount, String paymentToken) {
            calls.add(new Call(reference, accountId, amount, paymentToken));
        }

        @Override public void collectAgain(String reference, int retry) {}

        @Override public Optional<PaymentStatus> status(String reference) { return Optional.empty(); }

        @Override public Map<String, Money> collected(Collection<String> references) { return Map.of(); }
    }

    static final class FakeUpgradeIntents implements UpgradeIntents {
        final Map<String, UpgradeIntent> byReference = new LinkedHashMap<>();

        @Override
        public void save(UpgradeIntent intent) {
            byReference.putIfAbsent(intent.paymentReference(), intent);
        }

        @Override
        public Optional<UpgradeIntent> findByReference(String paymentReference) {
            return Optional.ofNullable(byReference.get(paymentReference));
        }

        @Override
        public void markCompleted(String paymentReference, Instant completedAt) {
            UpgradeIntent intent = byReference.get(paymentReference);
            if (intent != null && !intent.completed()) {
                byReference.put(paymentReference, intent.completed(completedAt));
            }
        }

        @Override
        public Optional<UpgradeIntent> findOpenForPeriodInvoice(UUID periodInvoiceId) {
            return byReference.values().stream()
                    .filter(i -> !i.completed() && periodInvoiceId.equals(i.periodInvoiceId()))
                    .findFirst();
        }
    }

    static final class FakeCreditNotes implements CreditNotes {
        final Map<UUID, CreditNote> byId = new LinkedHashMap<>();
        int addCalls = 0;

        @Override
        public void add(CreditNote creditNote) {
            addCalls++;
            byId.put(creditNote.id(), creditNote);
        }

        @Override
        public Optional<CreditNote> find(UUID creditNoteId) {
            return Optional.ofNullable(byId.get(creditNoteId));
        }

        @Override
        public List<CreditNote> ofInvoice(UUID invoiceId) {
            return byId.values().stream().filter(n -> n.invoiceId().equals(invoiceId)).toList();
        }

        @Override
        public List<CreditNote> appliedTo(UUID invoiceId) {
            return byId.values().stream()
                    .filter(n -> invoiceId.equals(n.appliedToInvoiceId()))
                    .toList();
        }
    }

    static final class FakeEvents implements ApplicationEventPublisher {
        final List<Object> published = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            published.add(event);
        }
    }

    // --- Fixtures ---

    static Invoice paidPeriodInvoice(UUID accountId, UUID subscriptionId) {
        Invoice invoice = Invoice.issue(accountId, subscriptionId, "BASIC", PERIOD,
                List.of(InvoiceLine.of("Price", Money.of(999, "AED"))),
                new PricingReason("BASIC", 1, "fixed-price-phase", Map.of()),
                PERIOD_START, PERIOD_END, NOW, NOW);
        invoice.paid(NOW);
        return invoice;
    }

    static Invoice unpaidPeriodInvoice(UUID accountId, UUID subscriptionId) {
        return Invoice.issue(accountId, subscriptionId, "BASIC", PERIOD,
                List.of(InvoiceLine.of("Price", Money.of(999, "AED"))),
                new PricingReason("BASIC", 1, "fixed-price-phase", Map.of()),
                PERIOD_START, PERIOD_END, NOW, NOW);
    }

    static SubscriptionSnapshot snapshot(UUID subscriptionId, UUID accountId, boolean active) {
        return new SubscriptionSnapshot(subscriptionId, accountId, "BASIC", 1, active, PERIOD, PERIOD_START, PERIOD_END);
    }

    static PriceQuote goldenQuote(UUID subscriptionId) {
        return new PriceQuote(subscriptionId, "PRO", 2, new Period(PERIOD, PERIOD_START, PERIOD_END),
                List.of(new PriceLine("Change", GOLDEN_NET)), GOLDEN_NET,
                new QuoteReason("PRO", 2, "proration", Map.of()));
    }

    static FakeAccounts accounts(UUID accountId) {
        return new FakeAccounts(accountId, Optional.of("tok_123"));
    }

    static UpgradeService upgradeService(Optional<SubscriptionSnapshot> subscription, FakeInvoices invoices,
            FakeAccounts accounts, FakeOffers offers, FakePricing pricing, FakePayments payments) {
        return upgradeService(new StubSubscriptionApi(subscription), invoices, accounts, offers, pricing, payments,
                new FakeUpgradeIntents(), new FakeCreditNotes(), new FakeEvents());
    }

    static UpgradeService upgradeService(SubscriptionApi subscriptions, FakeInvoices invoices, FakeAccounts accounts,
            FakeOffers offers, FakePricing pricing, FakePayments payments, FakeUpgradeIntents intents,
            FakeCreditNotes credits, FakeEvents events) {
        return new UpgradeService(subscriptions, invoices, accounts, offers, pricing, payments, intents, credits,
                events, Clock.fixed(NOW, ZoneId.of("UTC")));
    }

    // --- Tests ---

    @Test
    void anActiveSubscriptionIsChargedTheChangeQuote() {
        UUID accountId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Invoice periodInvoice = paidPeriodInvoice(accountId, subscriptionId);
        FakeInvoices invoices = new FakeInvoices();
        invoices.add(periodInvoice);
        FakeAccounts accounts = accounts(accountId);
        FakeOffers offers = new FakeOffers(OptionalInt.of(2));
        FakePricing pricing = new FakePricing(goldenQuote(subscriptionId));
        FakePayments payments = new FakePayments();
        FakeUpgradeIntents intents = new FakeUpgradeIntents();

        upgradeService(new StubSubscriptionApi(Optional.of(snapshot(subscriptionId, accountId, true))), invoices,
                accounts, offers, pricing, payments, intents, new FakeCreditNotes(), new FakeEvents())
                .upgrade(subscriptionId, "PRO");

        assertThat(payments.calls).hasSize(1);
        FakePayments.Call call = payments.calls.get(0);
        assertThat(call.reference()).isEqualTo("INV-" + Invoice.idOfAdjustment(periodInvoice.id(), 1));
        assertThat(call.accountId()).isEqualTo(accountId);
        assertThat(call.amount()).isEqualTo(GOLDEN_NET);
        assertThat(call.token()).isEqualTo("tok_123");
        assertThat(invoices.addIfAbsentCalls).isZero();
        assertThat(intents.byReference).containsOnlyKeys(call.reference());
        UpgradeIntent intent = intents.byReference.get(call.reference());
        assertThat(intent.subscriptionId()).isEqualTo(subscriptionId);
        assertThat(intent.newPlanId()).isEqualTo("PRO");
        assertThat(intent.newOfferVersion()).isEqualTo(2);
        assertThat(intent.periodInvoiceId()).isEqualTo(periodInvoice.id());
        assertThat(intent.quote().total()).isEqualTo(GOLDEN_NET);
        assertThat(intent.completed()).isFalse();
        assertThat(pricing.lastChangeRequest.effectiveDate()).isEqualTo(EFFECTIVE_DATE);
        assertThat(pricing.lastChangeRequest.period().number()).isEqualTo(PERIOD);
        assertThat(pricing.lastChangeRequest.period().start()).isEqualTo(PERIOD_START);
        assertThat(pricing.lastChangeRequest.period().end()).isEqualTo(PERIOD_END);
        assertThat(pricing.lastChangeRequest.subscriptionId()).isEqualTo(subscriptionId);
        assertThat(pricing.lastChangeRequest.accountId()).isEqualTo(accountId);
        assertThat(pricing.lastChangeRequest.oldOfferId()).isEqualTo("BASIC");
        assertThat(pricing.lastChangeRequest.oldOfferVersion()).isEqualTo(1);
        assertThat(pricing.lastChangeRequest.newOfferId()).isEqualTo("PRO");
        assertThat(pricing.lastChangeRequest.newOfferVersion()).isEqualTo(2);
    }

    @Test
    void anUnpaidPeriodInvoiceIsRefusedAndNothingIsCollected() {
        UUID accountId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Invoice periodInvoice = unpaidPeriodInvoice(accountId, subscriptionId);
        FakeInvoices invoices = new FakeInvoices();
        invoices.add(periodInvoice);
        FakePayments payments = new FakePayments();

        assertThatThrownBy(() -> upgradeService(Optional.of(snapshot(subscriptionId, accountId, true)), invoices,
                accounts(accountId), new FakeOffers(OptionalInt.of(2)),
                new FakePricing(goldenQuote(subscriptionId)), payments).upgrade(subscriptionId, "PRO"))
                .isInstanceOf(PeriodInvoiceUnpaidException.class);
        assertThat(payments.calls).isEmpty();
        assertThat(invoices.addIfAbsentCalls).isZero();
    }

    @Test
    void aNonActiveSubscriptionIsRefusedAndNothingIsCollected() {
        UUID accountId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Invoice periodInvoice = paidPeriodInvoice(accountId, subscriptionId);
        FakeInvoices invoices = new FakeInvoices();
        invoices.add(periodInvoice);
        FakePayments payments = new FakePayments();

        assertThatThrownBy(() -> upgradeService(Optional.of(snapshot(subscriptionId, accountId, false)), invoices,
                accounts(accountId), new FakeOffers(OptionalInt.of(2)),
                new FakePricing(goldenQuote(subscriptionId)), payments).upgrade(subscriptionId, "PRO"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Subscription " + subscriptionId);
        assertThat(payments.calls).isEmpty();
        assertThat(invoices.addIfAbsentCalls).isZero();
    }

    @Test
    void anUnknownSubscriptionIsRefusedAndNothingIsCollected() {
        UUID accountId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        FakeInvoices invoices = new FakeInvoices();
        FakePayments payments = new FakePayments();

        assertThatThrownBy(() -> upgradeService(Optional.empty(), invoices, accounts(accountId),
                new FakeOffers(OptionalInt.of(2)), new FakePricing(goldenQuote(subscriptionId)), payments)
                .upgrade(subscriptionId, "PRO"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No subscription " + subscriptionId);
        assertThat(payments.calls).isEmpty();
        assertThat(invoices.addIfAbsentCalls).isZero();
    }

    @Test
    void aDifferentPlanWhileAnOpenIntentExistsIsRefused() {
        UUID accountId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Invoice periodInvoice = paidPeriodInvoice(accountId, subscriptionId);
        FakeInvoices invoices = new FakeInvoices();
        invoices.add(periodInvoice);
        FakeAccounts accounts = accounts(accountId);
        FakeOffers offers = new FakeOffers(OptionalInt.of(2));
        FakePricing pricing = new FakePricing(goldenQuote(subscriptionId));
        FakePayments payments = new FakePayments();
        FakeUpgradeIntents intents = new FakeUpgradeIntents();
        String reference = "INV-" + Invoice.idOfAdjustment(periodInvoice.id(), 1);
        intents.save(UpgradeIntent.place(reference, subscriptionId, accountId, "PRO", 2, periodInvoice.id(),
                goldenQuote(subscriptionId)));

        assertThatThrownBy(() -> upgradeService(new StubSubscriptionApi(Optional.of(snapshot(subscriptionId, accountId, true))),
                invoices, accounts, offers, pricing, payments, intents, new FakeCreditNotes(), new FakeEvents())
                .upgrade(subscriptionId, "VIP"))
                .isInstanceOf(UpgradeAlreadyInProgressException.class);
        assertThat(payments.calls).isEmpty();
        assertThat(intents.byReference).containsOnlyKeys(reference);
    }

    @Test
    void aRetryOfTheSamePlanWhileTheIntentIsOpenChargesTheSameReferenceAgain() {
        UUID accountId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Invoice periodInvoice = paidPeriodInvoice(accountId, subscriptionId);
        FakeInvoices invoices = new FakeInvoices();
        invoices.add(periodInvoice);
        FakePayments payments = new FakePayments();
        FakeUpgradeIntents intents = new FakeUpgradeIntents();
        String reference = "INV-" + Invoice.idOfAdjustment(periodInvoice.id(), 1);
        intents.save(UpgradeIntent.place(reference, subscriptionId, accountId, "PRO", 2, periodInvoice.id(),
                goldenQuote(subscriptionId)));

        upgradeService(new StubSubscriptionApi(Optional.of(snapshot(subscriptionId, accountId, true))), invoices,
                accounts(accountId), new FakeOffers(OptionalInt.of(2)),
                new FakePricing(goldenQuote(subscriptionId)), payments, intents, new FakeCreditNotes(), new FakeEvents())
                .upgrade(subscriptionId, "PRO");

        assertThat(payments.calls).singleElement().satisfies(call ->
                assertThat(call.reference()).isEqualTo(reference));
        assertThat(intents.byReference).containsOnlyKeys(reference);
    }

    @Test
    void aFurtherUpgradeUsesTheNextSequence() {
        UUID accountId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Invoice periodInvoice = paidPeriodInvoice(accountId, subscriptionId);
        Invoice adjustment = Invoice.issueAdjustment(periodInvoice, 1, "PRO",
                List.of(InvoiceLine.of("Change", Money.of(100, "AED"))),
                new PricingReason("PRO", 2, "proration", Map.of()), NOW, NOW);
        FakeInvoices invoices = new FakeInvoices();
        invoices.add(periodInvoice);
        invoices.add(adjustment);
        FakePayments payments = new FakePayments();
        FakeUpgradeIntents intents = new FakeUpgradeIntents();

        upgradeService(new StubSubscriptionApi(Optional.of(snapshot(subscriptionId, accountId, true))), invoices,
                accounts(accountId), new FakeOffers(OptionalInt.of(2)),
                new FakePricing(goldenQuote(subscriptionId)), payments, intents, new FakeCreditNotes(), new FakeEvents())
                .upgrade(subscriptionId, "VIP");

        String expectedReference = "INV-" + Invoice.idOfAdjustment(periodInvoice.id(), 2);
        assertThat(payments.calls).singleElement().satisfies(call ->
                assertThat(call.reference()).isEqualTo(expectedReference));
        assertThat(intents.byReference).containsOnlyKeys(expectedReference);
        UpgradeIntent intent = intents.byReference.get(expectedReference);
        assertThat(intent.periodInvoiceId()).isEqualTo(periodInvoice.id());
        assertThat(intent.newPlanId()).isEqualTo("VIP");
    }
}
