package com.tally.invoicing.adapter.persistence;

import com.tally.invoicing.domain.Invoice;
import com.tally.invoicing.domain.Invoices;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Spring Data implements {@link Invoices}. */
interface JpaInvoices extends JpaRepository<Invoice, UUID>, Invoices {

    @Override
    default boolean insertIfAbsent(Invoice invoice) {
        return insertRow(invoice.id(), invoice.accountId(), invoice.subscriptionId(), invoice.planId(),
                invoice.status().name(), invoice.amount().minorUnits(), invoice.amount().currency().getCurrencyCode(),
                invoice.periodStart(), invoice.periodEnd(), invoice.paymentReference(), invoice.issuedAt(),
                invoice.billedFor()) == 1;
    }

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO invoicing.invoice (id, account_id, subscription_id, plan_id, status, amount_minor, currency,
                                           period_start, period_end, payment_reference, issued_at, billed_for)
            VALUES (:id, :accountId, :subscriptionId, :planId, :status, :amountMinor, :currency,
                    :periodStart, :periodEnd, :paymentReference, :issuedAt, :billedFor)
            ON CONFLICT ON CONSTRAINT invoice_one_per_account_and_period DO NOTHING
            """)
    int insertRow(UUID id, UUID accountId, UUID subscriptionId, String planId, String status, long amountMinor,
            String currency, LocalDate periodStart, LocalDate periodEnd, String paymentReference, Instant issuedAt,
            Instant billedFor);
}
