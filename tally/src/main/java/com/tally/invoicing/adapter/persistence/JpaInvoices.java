package com.tally.invoicing.adapter.persistence;

import com.tally.invoicing.domain.Invoice;
import com.tally.invoicing.domain.InvoiceLine;
import com.tally.invoicing.domain.Invoices;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Spring Data implements {@link Invoices}; its method names stay here (CLAUDE.md rule 14). */
interface JpaInvoices extends JpaRepository<Invoice, UUID>, Invoices {

    @Override
    default Optional<Invoice> forPaymentReference(String paymentReference) {
        return findByPaymentReference(paymentReference);
    }

    @Override
    default List<Invoice> ofAccount(UUID accountId) {
        return findByAccountIdOrderByPeriodStart(accountId);
    }

    @Override
    default List<Invoice> ofSubscription(UUID subscriptionId) {
        return findBySubscriptionIdOrderByPeriodStart(subscriptionId);
    }

    @Override
    default List<Invoice> issuedBetween(Instant from, Instant to) {
        return findByIssuedAtGreaterThanEqualAndIssuedAtLessThan(from, to);
    }

    Optional<Invoice> findByPaymentReference(String paymentReference);

    List<Invoice> findByAccountIdOrderByPeriodStart(UUID accountId);

    List<Invoice> findBySubscriptionIdOrderByPeriodStart(UUID subscriptionId);

    List<Invoice> findByIssuedAtGreaterThanEqualAndIssuedAtLessThan(Instant from, Instant to);

    @Override
    default boolean addIfAbsent(Invoice invoice) {
        boolean inserted = insertRow(invoice.id(), invoice.accountId(), invoice.subscriptionId(), invoice.planId(),
                invoice.status().name(), invoice.amount().minorUnits(), invoice.amount().currency().getCurrencyCode(),
                invoice.periodStart(), invoice.periodEnd(), invoice.paymentReference(), invoice.issuedAt(),
                invoice.billedFor(), invoice.offerVersion(), new PricingReasonJson().convertToDatabaseColumn(invoice.quoteReason()),
                invoice.periodNumber(), invoice.paidAt()) == 1;
        if (inserted) {
            // Same transaction as the invoice row: both commit, or neither does.
            List<InvoiceLine> lines = invoice.lines();
            for (int i = 0; i < lines.size(); i++) {
                InvoiceLine line = lines.get(i);
                insertLine(invoice.id(), i, line.description(), line.amountMinor(), line.currency());
            }
        }
        return inserted;
    }

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO invoicing.invoice (id, account_id, subscription_id, plan_id, status, amount_minor, currency,
                                           period_start, period_end, payment_reference, issued_at, billed_for,
                                           offer_version, quote_reason, period_number, paid_at)
            VALUES (:id, :accountId, :subscriptionId, :planId, :status, :amountMinor, :currency,
                    :periodStart, :periodEnd, :paymentReference, :issuedAt, :billedFor,
                    :offerVersion, CAST(:quoteReason AS jsonb), :periodNumber, :paidAt)
            ON CONFLICT DO NOTHING
            """)
    int insertRow(UUID id, UUID accountId, UUID subscriptionId, String planId, String status, long amountMinor,
            String currency, LocalDate periodStart, LocalDate periodEnd, String paymentReference, Instant issuedAt,
            Instant billedFor, Integer offerVersion, String quoteReason, Integer periodNumber, Instant paidAt);

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO invoicing.invoice_line (invoice_id, line_number, description, amount_minor, currency)
            VALUES (:invoiceId, :lineNumber, :description, :amountMinor, :currency)
            """)
    int insertLine(UUID invoiceId, int lineNumber, String description, long amountMinor, String currency);
}
