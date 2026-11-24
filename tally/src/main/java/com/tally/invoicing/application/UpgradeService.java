package com.tally.invoicing.application;

import com.tally.account.AccountApi;
import com.tally.account.AccountView;
import com.tally.account.UnknownAccountException;
import com.tally.invoicing.CreditNoteIssued;
import com.tally.invoicing.InvoiceIssued;
import com.tally.invoicing.InvoicePaid;
import com.tally.invoicing.PricingReason;
import com.tally.invoicing.domain.CreditNote;
import com.tally.invoicing.domain.CreditNotes;
import com.tally.invoicing.domain.Invoice;
import com.tally.invoicing.domain.InvoiceLine;
import com.tally.invoicing.domain.Invoices;
import com.tally.invoicing.domain.PeriodInvoicePaidGuard;
import com.tally.invoicing.domain.UpgradeIntent;
import com.tally.invoicing.domain.UpgradeIntents;
import com.tally.offers.ChangeRequest;
import com.tally.offers.Offers;
import com.tally.offers.Period;
import com.tally.offers.PriceQuote;
import com.tally.offers.Pricing;
import com.tally.offers.QuoteReason;
import com.tally.offers.UnknownPlanException;
import com.tally.payment.PaymentApi;
import com.tally.shared.Money;
import com.tally.subscription.SubscriptionApi;
import com.tally.subscription.SubscriptionSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Charges a mid-period plan change and, after that charge succeeds, issues the credit note and
 * adjustment and moves the subscription onto the new plan (ADR-041, ADR-042). {@link #upgrade} calls
 * Payment, so it runs outside a transaction (CLAUDE.md rule 6). {@link #completeAfterCharge} is
 * transactional, forward-only, and idempotent (decision 005).
 */
@Service
class UpgradeService {

    private static final Logger log = LoggerFactory.getLogger(UpgradeService.class);

    private static final int FIRST_ADJUSTMENT = 1;

    private final SubscriptionApi subscriptions;
    private final Invoices invoices;
    private final AccountApi accounts;
    private final Offers offers;
    private final Pricing pricing;
    private final PaymentApi payments;
    private final UpgradeIntents intents;
    private final CreditNotes credits;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    UpgradeService(SubscriptionApi subscriptions, Invoices invoices, AccountApi accounts, Offers offers,
            Pricing pricing, PaymentApi payments, UpgradeIntents intents, CreditNotes credits,
            ApplicationEventPublisher events, Clock clock) {
        this.subscriptions = subscriptions;
        this.invoices = invoices;
        this.accounts = accounts;
        this.offers = offers;
        this.pricing = pricing;
        this.payments = payments;
        this.intents = intents;
        this.credits = credits;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Charges {@code newPlanId} for the current period of the subscription, if it is ACTIVE and the period
     * invoice is paid. The reference is the first adjustment of that invoice, so a retry charges the same
     * reference (D1). The quote is remembered as an intent; documents and the plan change wait for the
     * charge to succeed (ADR-041).
     */
    void upgrade(UUID subscriptionId, String newPlanId) {
        SubscriptionSnapshot subscription = subscriptions.get(subscriptionId).orElseThrow(() ->
                new IllegalArgumentException("No subscription " + subscriptionId));
        if (!subscription.active()) {
            throw new IllegalStateException("Subscription " + subscription.id() + " is not active; upgrade is ACTIVE only");
        }
        Invoice periodInvoice = PeriodInvoicePaidGuard.requirePaid(
                invoices.find(Invoice.idOf(subscription.id(), subscription.periodNumber())),
                subscription.id(), subscription.periodNumber());
        int newVersion = offers.currentVersion(newPlanId, subscription.accountId())
                .orElseThrow(() -> new UnknownPlanException(newPlanId, subscription.accountId()));
        AccountView account = accounts.get(subscription.accountId()).orElseThrow(
                () -> new UnknownAccountException(subscription.accountId()));
        LocalDate effectiveDate = LocalDate.ofInstant(clock.instant(), account.timeZone());
        PriceQuote quote = pricing.quoteChange(new ChangeRequest(subscription.id(), subscription.accountId(),
                subscription.planId(), subscription.offerVersion(), newPlanId, newVersion,
                new Period(subscription.periodNumber(), subscription.periodStart(), subscription.periodEnd()),
                effectiveDate));
        if (quote.total().minorUnits() <= 0) {
            throw new IllegalArgumentException("The upgrade of subscription " + subscription.id() + " is worth "
                    + quote.total() + ": nothing to collect (this command is an upgrade)");
        }
        String token = account.paymentToken().orElseThrow(() ->
                new IllegalStateException("Account " + subscription.accountId() + " has no payment token"));
        String reference = "INV-" + Invoice.idOfAdjustment(periodInvoice.id(), FIRST_ADJUSTMENT);
        Optional<UpgradeIntent> existing = intents.findByReference(reference);
        if (existing.map(UpgradeIntent::completed).orElse(false)) {
            return;
        }
        if (existing.isEmpty()) {
            intents.save(UpgradeIntent.place(reference, subscription.id(), subscription.accountId(), newPlanId,
                    newVersion, periodInvoice.id(), quote));
        }
        payments.requestCollection(reference, subscription.accountId(), quote.total(), token);
    }

    /**
     * Finishes an upgrade after its charge has succeeded: issues the credit note and the already-paid
     * adjustment, changes the plan, and marks the intent completed. A second call with the same
     * reference changes nothing twice.
     *
     * @return whether an intent for this reference was found
     */
    @Transactional
    boolean completeAfterCharge(String paymentReference) {
        Optional<UpgradeIntent> found = intents.findByReference(paymentReference);
        if (found.isEmpty()) {
            log.warn("Payment result for unknown reference {} ignored", paymentReference);
            return false;
        }
        UpgradeIntent intent = found.get();
        if (intent.completed()) {
            return true;
        }
        Instant now = clock.instant();
        Invoice periodInvoice = invoices.find(intent.periodInvoiceId()).orElseThrow(() ->
                new IllegalStateException("Period invoice " + intent.periodInvoiceId() + " is missing for upgrade "
                        + paymentReference));
        Invoice adjustment = issuePaidAdjustment(intent, periodInvoice, now);
        issueCreditNote(intent, periodInvoice, adjustment.id(), now);
        subscriptions.changePlan(intent.subscriptionId(), intent.newPlanId(), intent.newOfferVersion());
        intents.markCompleted(paymentReference, now);
        return true;
    }

    private void issueCreditNote(UpgradeIntent intent, Invoice periodInvoice, UUID appliedToInvoiceId,
            Instant now) {
        List<InvoiceLine> creditLines = intent.quote().lines().stream()
                .filter(line -> line.amount().minorUnits() < 0)
                .map(line -> InvoiceLine.of(line.description(),
                        new Money(-line.amount().minorUnits(), line.amount().currency())))
                .toList();
        if (creditLines.isEmpty()) {
            return;
        }
        UUID id = CreditNote.idOfUpgradeCredit(periodInvoice.id(), FIRST_ADJUSTMENT);
        if (credits.find(id).isPresent()) {
            return;
        }
        Money available = periodInvoice.amount();
        for (CreditNote existing : credits.ofInvoice(periodInvoice.id())) {
            available = available.minus(existing.amount());
        }
        CreditNote creditNote = CreditNote.issueWithId(id, periodInvoice.id(), periodInvoice.accountId(), creditLines,
                available, now, appliedToInvoiceId);
        credits.add(creditNote);
        events.publishEvent(new CreditNoteIssued(UUID.randomUUID(), now, creditNote.accountId(), creditNote.id(),
                creditNote.invoiceId(), creditNote.amount(), CreditNote.ISSUED_VERSION));
    }

    private Invoice issuePaidAdjustment(UpgradeIntent intent, Invoice periodInvoice, Instant now) {
        PriceQuote quote = intent.quote();
        List<InvoiceLine> lines = quote.lines().stream()
                .filter(line -> line.amount().minorUnits() > 0)
                .map(line -> InvoiceLine.of(line.description(), line.amount()))
                .toList();
        if (lines.isEmpty()) {
            throw new IllegalStateException("The upgrade quote for " + intent.paymentReference()
                    + " has no charge line to put on the adjustment invoice");
        }
        PricingReason reason = reasonOf(quote.reason());
        Invoice adjustment = Invoice.issueAdjustment(periodInvoice, FIRST_ADJUSTMENT, intent.newPlanId(), lines, reason,
                now, now);
        adjustment.paid(now);
        if (invoices.addIfAbsent(adjustment)) {
            events.publishEvent(new InvoiceIssued(UUID.randomUUID(), now, adjustment.accountId(), adjustment.id(),
                    adjustment.paymentReference(), adjustment.amount(), Invoice.ISSUED_VERSION));
            events.publishEvent(new InvoicePaid(UUID.randomUUID(), now, adjustment.accountId(), adjustment.id(),
                    adjustment.paymentReference(), adjustment.version()));
        }
        return invoices.find(adjustment.id()).orElse(adjustment);
    }

    private static PricingReason reasonOf(QuoteReason reason) {
        return new PricingReason(reason.offerId(), reason.offerVersion(), reason.kind(), reason.flags());
    }
}
