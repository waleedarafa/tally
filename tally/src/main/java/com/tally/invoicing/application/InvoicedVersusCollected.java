package com.tally.invoicing.application;

import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.domain.Invoice;
import com.tally.invoicing.domain.Invoices;
import com.tally.payment.PaymentApi;
import com.tally.platform.InvariantCheck;
import com.tally.shared.Money;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-024: collected versus invoiced (slice 1 report D20, Q4). Invoicing owns the mapping from
 * payment references to invoices (FR-014), so it asks Payment, through its API, what was
 * collected for its invoices' references, and compares. An invoice is a violation when:
 * <ul>
 *   <li>it is PAID, but nothing was collected for its reference, or a different amount was;</li>
 *   <li>it is not PAID, but money was collected for its reference.</li>
 * </ul>
 * Checked over invoices issued in the last {@link #WINDOW}, except the last {@link #SETTLE}: a payment
 * that has just succeeded reaches its invoice through an event, moments later. The daily runs'
 * windows overlap, so no invoice escapes being checked.
 *
 * <p>This compares Tally's two records with each other. It cannot see a charge the provider
 * reported wrongly (Spike B, B4); that needs the provider's settlement report (report R2).
 */
@Component
class InvoicedVersusCollected implements InvariantCheck {

    static final Duration WINDOW = Duration.ofDays(2);
    static final Duration SETTLE = Duration.ofHours(1);
    private static final int BATCH = 1000;

    private static final Logger log = LoggerFactory.getLogger(InvoicedVersusCollected.class);

    private final Invoices invoices;
    private final PaymentApi payments;
    private final Clock clock;

    InvoicedVersusCollected(Invoices invoices, PaymentApi payments, Clock clock) {
        this.invoices = invoices;
        this.payments = payments;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "invoicing.invoiced-versus-collected";
    }

    @Override
    public String table() {
        return "invoicing.jobs";
    }

    @Override
    @Transactional(readOnly = true)
    public long violations() {
        Instant now = clock.instant();
        List<Invoice> checked = invoices.findByIssuedAtGreaterThanEqualAndIssuedAtLessThan(
                now.minus(WINDOW), now.minus(SETTLE));
        Map<String, Money> collected = new HashMap<>();
        for (int i = 0; i < checked.size(); i += BATCH) {
            collected.putAll(payments.collected(checked.subList(i, Math.min(i + BATCH, checked.size())).stream()
                    .map(Invoice::paymentReference).toList()));
        }
        long violations = 0;
        for (Invoice invoice : checked) {
            Money paid = collected.get(invoice.paymentReference());
            boolean holds = invoice.status() == InvoiceStatus.PAID ? invoice.amount().equals(paid) : paid == null;
            if (!holds) {
                violations++;
                log.warn("Invoiced versus collected: invoice {} is {} for {}, but Payment collected {} for {}",
                        invoice.id(), invoice.status(), invoice.amount(), paid == null ? "nothing" : paid,
                        invoice.paymentReference());
            }
        }
        return violations;
    }
}
