package com.tally.invoicing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.invoicing.CreditNoteIssued;
import com.tally.invoicing.InvoiceIssued;
import com.tally.invoicing.InvoicePaid;
import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.PricingReason;
import com.tally.invoicing.application.UpgradeCommandTest.FakeAccounts;
import com.tally.invoicing.application.UpgradeCommandTest.FakeCreditNotes;
import com.tally.invoicing.application.UpgradeCommandTest.FakeEvents;
import com.tally.invoicing.application.UpgradeCommandTest.FakeInvoices;
import com.tally.invoicing.application.UpgradeCommandTest.FakeOffers;
import com.tally.invoicing.application.UpgradeCommandTest.FakePayments;
import com.tally.invoicing.application.UpgradeCommandTest.FakePricing;
import com.tally.invoicing.application.UpgradeCommandTest.FakeUpgradeIntents;
import com.tally.invoicing.domain.CreditNote;
import com.tally.invoicing.domain.Invoice;
import com.tally.invoicing.domain.InvoiceKind;
import com.tally.invoicing.domain.InvoiceLine;
import com.tally.invoicing.domain.TaxQuote;
import com.tally.invoicing.domain.UpgradeIntent;
import com.tally.offers.Period;
import com.tally.offers.PriceLine;
import com.tally.offers.PriceQuote;
import com.tally.offers.QuoteReason;
import com.tally.payment.PaymentSucceeded;
import com.tally.shared.Money;
import com.tally.shared.Ratio;
import com.tally.subscription.EndReason;
import com.tally.subscription.SubscriptionApi;
import com.tally.subscription.SubscriptionSnapshot;
import com.tally.subscription.SubscriptionView;
import com.tally.subscription.TimelineEntry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * After a successful upgrade charge: the credit note and already-paid adjustment are issued, the plan
 * changes, and a second completion is a no-op (ADR-041, decision 005).
 */
class UpgradeCompletionTest {

    static final Instant NOW = UpgradeCommandTest.NOW;
    static final int PERIOD = UpgradeCommandTest.PERIOD;
    static final LocalDate PERIOD_START = UpgradeCommandTest.PERIOD_START;
    static final LocalDate PERIOD_END = UpgradeCommandTest.PERIOD_END;
    static final Money CREDIT = Money.of(600, "AED");
    static final Money CHARGE = Money.of(1000, "AED");
    static final Money NET = Money.of(400, "AED");
    static final Money PERIOD_TOTAL = Money.of(1200, "AED");

    static final class RecordingSubscriptions implements SubscriptionApi {
        SubscriptionSnapshot snapshot;
        final List<String> changePlanCalls = new ArrayList<>();

        RecordingSubscriptions(SubscriptionSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public Optional<SubscriptionSnapshot> get(UUID subscriptionId) {
            return Optional.of(snapshot);
        }

        @Override
        public boolean changePlan(UUID subscriptionId, String planId, int offerVersion) {
            changePlanCalls.add(planId + ":" + offerVersion);
            if (!snapshot.active() || (snapshot.planId().equals(planId) && snapshot.offerVersion() == offerVersion)) {
                return false;
            }
            snapshot = new SubscriptionSnapshot(snapshot.id(), snapshot.accountId(), planId, offerVersion,
                    snapshot.active(), snapshot.periodNumber(), snapshot.periodStart(), snapshot.periodEnd());
            return true;
        }

        @Override public SubscriptionView subscribe(UUID accountId, String planId) { throw new UnsupportedOperationException(); }
        @Override public SubscriptionView subscribe(UUID accountId, String planId, UUID signUpKey) { throw new UnsupportedOperationException(); }
        @Override public SubscriptionView startTrial(UUID accountId, String planId) { throw new UnsupportedOperationException(); }
        @Override public boolean endTrial(UUID subscriptionId) { throw new UnsupportedOperationException(); }
        @Override public boolean cancel(UUID subscriptionId) { throw new UnsupportedOperationException(); }
        @Override public boolean periodEnded(UUID subscriptionId) { throw new UnsupportedOperationException(); }
        @Override public List<UUID> endLiveSubscriptions(UUID accountId, EndReason reason) { throw new UnsupportedOperationException(); }
        @Override public boolean end(UUID subscriptionId, EndReason reason) { throw new UnsupportedOperationException(); }
        @Override public List<TimelineEntry> timeline(UUID subscriptionId) { throw new UnsupportedOperationException(); }
    }

    static Invoice paidPeriodInvoice(UUID accountId, UUID subscriptionId) {
        Invoice invoice = Invoice.issue(accountId, subscriptionId, "BASIC", PERIOD,
                List.of(InvoiceLine.of("Price", PERIOD_TOTAL)),
                new PricingReason("BASIC", 1, "fixed-price-phase", Map.of()),
                PERIOD_START, PERIOD_END, NOW, NOW);
        invoice.paid(NOW);
        return invoice;
    }

    static PriceQuote goldenQuote(UUID subscriptionId) {
        return new PriceQuote(subscriptionId, "PRO", 2, new Period(PERIOD, PERIOD_START, PERIOD_END),
                List.of(new PriceLine("Unused Basic", Money.of(-600, "AED")),
                        new PriceLine("Premium remaining", Money.of(1000, "AED"))),
                NET, new QuoteReason("PRO", 2, "proration", Map.of()));
    }

    @Test
    void aSuccessfulChargeIssuesDocumentsAndChangesThePlan() {
        UUID accountId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Invoice periodInvoice = paidPeriodInvoice(accountId, subscriptionId);
        FakeInvoices invoices = new FakeInvoices();
        invoices.add(periodInvoice);
        FakeUpgradeIntents intents = new FakeUpgradeIntents();
        FakeCreditNotes credits = new FakeCreditNotes();
        FakeEvents events = new FakeEvents();
        FakePayments payments = new FakePayments();
        RecordingSubscriptions subscriptions = new RecordingSubscriptions(
                UpgradeCommandTest.snapshot(subscriptionId, accountId, true));
        PriceQuote quote = goldenQuote(subscriptionId);
        String reference = "INV-" + Invoice.idOfAdjustment(periodInvoice.id(), 1);
        intents.save(UpgradeIntent.place(reference, subscriptionId, accountId, "PRO", 2, periodInvoice.id(), quote));

        UpgradeCommandTest.upgradeService(subscriptions, invoices, new FakeAccounts(accountId, Optional.of("tok_123")),
                new FakeOffers(OptionalInt.of(2)), new FakePricing(quote), payments, intents, credits, events)
                .completeAfterCharge(reference);

        assertThat(payments.calls).isEmpty();
        assertThat(subscriptions.changePlanCalls).containsExactly("PRO:2");
        assertThat(subscriptions.snapshot.planId()).isEqualTo("PRO");
        assertThat(subscriptions.snapshot.offerVersion()).isEqualTo(2);
        assertThat(intents.byReference.get(reference).completed()).isTrue();
        assertThat(intents.byReference.get(reference).completedAt()).isEqualTo(NOW);

        UUID creditNoteId = CreditNote.idOfUpgradeCredit(periodInvoice.id(), 1);
        assertThat(credits.find(creditNoteId)).isPresent();
        assertThat(credits.find(creditNoteId).orElseThrow().amount()).isEqualTo(CREDIT);
        assertThat(credits.find(creditNoteId).orElseThrow().invoiceId()).isEqualTo(periodInvoice.id());
        assertThat(credits.find(creditNoteId).orElseThrow().lines()).hasSize(1);
        assertThat(credits.find(creditNoteId).orElseThrow().lines().get(0).description()).isEqualTo("Unused Basic");

        Invoice adjustment = invoices.find(Invoice.idOfAdjustment(periodInvoice.id(), 1)).orElseThrow();
        assertThat(adjustment.invoiceType()).isEqualTo(InvoiceKind.ADJUSTMENT);
        assertThat(adjustment.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(adjustment.amount()).isEqualTo(CHARGE);
        assertThat(adjustment.lines()).hasSize(1);
        assertThat(adjustment.lines().get(0).description()).isEqualTo("Premium remaining");
        assertThat(adjustment.paymentReference()).isEqualTo(reference);
        assertThat(adjustment.planId()).isEqualTo("PRO");
        assertThat(invoices.addIfAbsentCalls).isEqualTo(1);
        assertThat(credits.find(creditNoteId).orElseThrow().appliedToInvoiceId()).isEqualTo(adjustment.id());
        assertThat(credits.appliedTo(adjustment.id())).containsExactly(credits.find(creditNoteId).orElseThrow());
        // The charge was the net; the applied credit settles the rest of the adjustment.
        assertThat(adjustment.amount().minus(credits.find(creditNoteId).orElseThrow().amount())).isEqualTo(NET);

        // The adjustment is issued first so the credit note can reference it (applied-to FK).
        assertThat(events.published).hasSize(3);
        assertThat(events.published.get(0)).isInstanceOf(InvoiceIssued.class);
        InvoiceIssued issued = (InvoiceIssued) events.published.get(0);
        assertThat(issued.invoiceId()).isEqualTo(adjustment.id());
        assertThat(issued.amount()).isEqualTo(CHARGE);
        assertThat(events.published.get(1)).isInstanceOf(InvoicePaid.class);
        assertThat(((InvoicePaid) events.published.get(1)).invoiceId()).isEqualTo(adjustment.id());
        assertThat(events.published.get(2)).isInstanceOf(CreditNoteIssued.class);
        CreditNoteIssued creditIssued = (CreditNoteIssued) events.published.get(2);
        assertThat(creditIssued.creditNoteId()).isEqualTo(creditNoteId);
        assertThat(creditIssued.amount()).isEqualTo(CREDIT);
    }

    @Test
    void completionWithVatIssuesTaxedAdjustmentAndTaxInclusiveCredit() {
        UUID accountId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Invoice periodInvoice = paidPeriodInvoice(accountId, subscriptionId);
        FakeInvoices invoices = new FakeInvoices();
        invoices.add(periodInvoice);
        FakeUpgradeIntents intents = new FakeUpgradeIntents();
        FakeCreditNotes credits = new FakeCreditNotes();
        FakeEvents events = new FakeEvents();
        RecordingSubscriptions subscriptions = new RecordingSubscriptions(
                UpgradeCommandTest.snapshot(subscriptionId, accountId, true));
        PriceQuote quote = goldenQuote(subscriptionId);
        TaxQuote tax = new TaxQuote(Ratio.of(5, 100), "VAT (5%)");
        String reference = "INV-" + Invoice.idOfAdjustment(periodInvoice.id(), 1);
        intents.save(UpgradeIntent.place(reference, subscriptionId, accountId, "PRO", 2, periodInvoice.id(),
                quote, tax));

        UpgradeCommandTest.upgradeService(subscriptions, invoices, new FakeAccounts(accountId, Optional.of("tok_123")),
                new FakeOffers(OptionalInt.of(2)), new FakePricing(quote), new FakePayments(), intents, credits, events)
                .completeAfterCharge(reference);

        UUID adjustmentId = Invoice.idOfAdjustment(periodInvoice.id(), 1);
        Invoice adjustment = invoices.find(adjustmentId).orElseThrow();
        assertThat(adjustment.invoiceType()).isEqualTo(InvoiceKind.ADJUSTMENT);
        assertThat(adjustment.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(adjustment.amount()).isEqualTo(Money.of(1050, "AED"));
        assertThat(adjustment.lines()).hasSize(2);
        assertThat(adjustment.lines().get(0).description()).isEqualTo("Premium remaining");
        assertThat(adjustment.lines().get(0).amount()).isEqualTo(Money.of(1000, "AED"));
        assertThat(adjustment.lines().get(1).description()).isEqualTo("VAT (5%)");
        assertThat(adjustment.lines().get(1).amount()).isEqualTo(Money.of(50, "AED"));
        assertThat(adjustment.planId()).isEqualTo("PRO");

        UUID creditNoteId = CreditNote.idOfUpgradeCredit(periodInvoice.id(), 1);
        CreditNote creditNote = credits.find(creditNoteId).orElseThrow();
        assertThat(creditNote.amount()).isEqualTo(Money.of(630, "AED"));
        assertThat(creditNote.lines()).hasSize(2);
        assertThat(creditNote.lines().get(0).description()).isEqualTo("Unused Basic");
        assertThat(creditNote.lines().get(0).amount()).isEqualTo(Money.of(600, "AED"));
        assertThat(creditNote.lines().get(1).description()).isEqualTo("VAT (5%)");
        assertThat(creditNote.lines().get(1).amount()).isEqualTo(Money.of(30, "AED"));
        assertThat(creditNote.invoiceId()).isEqualTo(periodInvoice.id());
        assertThat(creditNote.appliedToInvoiceId()).isEqualTo(adjustment.id());

        UpgradeIntent intent = intents.byReference.get(reference);
        assertThat(adjustment.amount().minus(creditNote.amount())).isEqualTo(Money.of(420, "AED"));
        assertThat(intent.charge()).isEqualTo(Money.of(420, "AED"));
    }

    @Test
    void aSecondCompletionChangesNothing() {
        UUID accountId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Invoice periodInvoice = paidPeriodInvoice(accountId, subscriptionId);
        FakeInvoices invoices = new FakeInvoices();
        invoices.add(periodInvoice);
        FakeUpgradeIntents intents = new FakeUpgradeIntents();
        FakeCreditNotes credits = new FakeCreditNotes();
        FakeEvents events = new FakeEvents();
        RecordingSubscriptions subscriptions = new RecordingSubscriptions(
                UpgradeCommandTest.snapshot(subscriptionId, accountId, true));
        PriceQuote quote = goldenQuote(subscriptionId);
        String reference = "INV-" + Invoice.idOfAdjustment(periodInvoice.id(), 1);
        intents.save(UpgradeIntent.place(reference, subscriptionId, accountId, "PRO", 2, periodInvoice.id(), quote));
        var service = UpgradeCommandTest.upgradeService(subscriptions, invoices,
                new FakeAccounts(accountId, Optional.of("tok_123")), new FakeOffers(OptionalInt.of(2)),
                new FakePricing(quote), new FakePayments(), intents, credits, events);

        assertThat(service.completeAfterCharge(reference)).isTrue();
        int adds = invoices.addIfAbsentCalls;
        int creditAdds = credits.addCalls;
        int eventsAfterFirst = events.published.size();
        assertThat(service.completeAfterCharge(reference)).isTrue();

        assertThat(credits.addCalls).isEqualTo(creditAdds);
        assertThat(invoices.addIfAbsentCalls).isEqualTo(adds);
        assertThat(events.published).hasSize(eventsAfterFirst);
        assertThat(credits.ofInvoice(periodInvoice.id())).hasSize(1);
        assertThat(subscriptions.changePlanCalls).containsExactly("PRO:2");
        assertThat(subscriptions.snapshot.planId()).isEqualTo("PRO");
    }

    @Test
    void anUnknownReferenceIsIgnored() {
        UUID accountId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        FakeInvoices invoices = new FakeInvoices();
        FakeUpgradeIntents intents = new FakeUpgradeIntents();
        FakeCreditNotes credits = new FakeCreditNotes();
        FakeEvents events = new FakeEvents();
        RecordingSubscriptions subscriptions = new RecordingSubscriptions(
                UpgradeCommandTest.snapshot(subscriptionId, accountId, true));

        boolean found = UpgradeCommandTest.upgradeService(subscriptions, invoices,
                new FakeAccounts(accountId, Optional.of("tok_123")), new FakeOffers(OptionalInt.of(2)),
                new FakePricing(goldenQuote(subscriptionId)), new FakePayments(), intents, credits, events)
                .completeAfterCharge("INV-unknown");

        assertThat(found).isFalse();
        assertThat(credits.addCalls).isZero();
        assertThat(invoices.addIfAbsentCalls).isZero();
        assertThat(subscriptions.changePlanCalls).isEmpty();
        assertThat(events.published).isEmpty();
    }

    @Test
    void anAlreadyPaidInvoiceIssuedDoesNotCollectAgain() {
        UUID accountId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Invoice periodInvoice = paidPeriodInvoice(accountId, subscriptionId);
        Invoice adjustment = Invoice.issueAdjustment(periodInvoice, 1, "PRO",
                List.of(InvoiceLine.of("Plan change to PRO", NET)),
                new PricingReason("PRO", 2, "proration", Map.of()), NOW, NOW);
        adjustment.paid(NOW);
        FakeInvoices invoices = new FakeInvoices();
        invoices.add(periodInvoice);
        invoices.add(adjustment);
        FakePayments payments = new FakePayments();
        FakeEvents events = new FakeEvents();
        var upgrades = UpgradeCommandTest.upgradeService(
                new RecordingSubscriptions(UpgradeCommandTest.snapshot(subscriptionId, accountId, true)),
                invoices, new FakeAccounts(accountId, Optional.of("tok_123")), new FakeOffers(OptionalInt.of(2)),
                new FakePricing(goldenQuote(subscriptionId)), payments, new FakeUpgradeIntents(),
                new FakeCreditNotes(), events);

        var listeners = new InvoicingListeners(invoices, null, new FakeAccounts(accountId, Optional.of("tok_123")),
                payments, upgrades, events, java.time.Clock.fixed(NOW, java.time.ZoneId.of("UTC")));
        listeners.on(new InvoiceIssued(UUID.randomUUID(), NOW, accountId, adjustment.id(),
                adjustment.paymentReference(), adjustment.amount(), Invoice.ISSUED_VERSION));

        assertThat(payments.calls).isEmpty();
    }

    @Test
    void paymentSucceededWithNoInvoiceCompletesTheUpgrade() {
        UUID accountId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Invoice periodInvoice = paidPeriodInvoice(accountId, subscriptionId);
        FakeInvoices invoices = new FakeInvoices();
        invoices.add(periodInvoice);
        FakeUpgradeIntents intents = new FakeUpgradeIntents();
        FakeCreditNotes credits = new FakeCreditNotes();
        FakeEvents events = new FakeEvents();
        FakePayments payments = new FakePayments();
        RecordingSubscriptions subscriptions = new RecordingSubscriptions(
                UpgradeCommandTest.snapshot(subscriptionId, accountId, true));
        PriceQuote quote = goldenQuote(subscriptionId);
        String reference = "INV-" + Invoice.idOfAdjustment(periodInvoice.id(), 1);
        intents.save(UpgradeIntent.place(reference, subscriptionId, accountId, "PRO", 2, periodInvoice.id(), quote));
        var upgrades = UpgradeCommandTest.upgradeService(subscriptions, invoices,
                new FakeAccounts(accountId, Optional.of("tok_123")), new FakeOffers(OptionalInt.of(2)),
                new FakePricing(quote), payments, intents, credits, events);
        var listeners = new InvoicingListeners(invoices, null, new FakeAccounts(accountId, Optional.of("tok_123")),
                payments, upgrades, events, java.time.Clock.fixed(NOW, java.time.ZoneId.of("UTC")));

        listeners.on(new PaymentSucceeded(UUID.randomUUID(), NOW, accountId, reference, NET, "chg_1", 1L));

        assertThat(subscriptions.snapshot.planId()).isEqualTo("PRO");
        assertThat(intents.byReference.get(reference).completed()).isTrue();
        assertThat(credits.ofInvoice(periodInvoice.id())).hasSize(1);
        assertThat(invoices.find(Invoice.idOfAdjustment(periodInvoice.id(), 1)).orElseThrow().status())
                .isEqualTo(InvoiceStatus.PAID);
    }
}
