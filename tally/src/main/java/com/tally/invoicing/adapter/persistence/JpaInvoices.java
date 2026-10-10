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

    /**
     * Inserts unless the invoice ID exists. Only the ID, which encodes subscription and period (I2), counts
     * as a duplicate: {@code ON CONFLICT (id)}. The payment reference and (subscription, period) are unique
     * too and derive from the same fact, but PostgreSQL absorbs only the named conflict: two concurrent
     * duplicates could meet on one of those first and fail. So a transaction-scoped advisory lock on the ID
     * serialises them; the second then meets the committed row on the ID, and adds nothing (rule 7, rule 11).
     */
    @Override
    default void update(Invoice invoice) {
        saveAndFlush(invoice);
    }

    @Override
    default boolean addIfAbsent(Invoice invoice) {
        lockInvoiceId(invoice.id().toString());
        boolean inserted = insertRow(invoice.id(), invoice.accountId(), invoice.subscriptionId(), invoice.planId(),
                invoice.status().name(), invoice.amount().minorUnits(), invoice.amount().currency().getCurrencyCode(),
                invoice.periodStart(), invoice.periodEnd(), invoice.paymentReference(), invoice.issuedAt(),
                invoice.billedFor(), invoice.offerVersion(), new PricingReasonJson().convertToDatabaseColumn(invoice.quoteReason()),
                invoice.periodNumber(), invoice.paidAt(), invoice.version()) == 1;
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
                                           offer_version, quote_reason, period_number, paid_at, version)
            VALUES (:id, :accountId, :subscriptionId, :planId, :status, :amountMinor, :currency,
                    :periodStart, :periodEnd, :paymentReference, :issuedAt, :billedFor,
                    :offerVersion, CAST(:quoteReason AS jsonb), :periodNumber, :paidAt, :version)
            ON CONFLICT (id) DO NOTHING
            """)
    int insertRow(UUID id, UUID accountId, UUID subscriptionId, String planId, String status, long amountMinor,
            String currency, LocalDate periodStart, LocalDate periodEnd, String paymentReference, Instant issuedAt,
            Instant billedFor, Integer offerVersion, String quoteReason, Integer periodNumber, Instant paidAt, long version);

    /** Held until the transaction ends; serialises concurrent inserts of the same invoice. */
    @Query(nativeQuery = true, value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:invoiceId, 0))) held")
    int lockInvoiceId(String invoiceId);

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO invoicing.invoice_line (invoice_id, line_number, description, amount_minor, currency)
            VALUES (:invoiceId, :lineNumber, :description, :amountMinor, :currency)
            """)
    int insertLine(UUID invoiceId, int lineNumber, String description, long amountMinor, String currency);
}
