package com.tally.invoicing.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    /**
     * Inserts {@code invoice} unless the account already has one for its billing date (D1), atomically:
     * a concurrent caller waits for the first insert to commit, then inserts nothing (ADR-028).
     * Returns whether this call inserted it.
     */
    default boolean insertIfAbsent(Invoice invoice) {
        return insertRow(invoice.id(), invoice.accountId(), invoice.subscriptionId(), invoice.planId(),
                invoice.status().name(), invoice.amount().minorUnits(), invoice.amount().currency().getCurrencyCode(),
                invoice.periodStart(), invoice.periodEnd(), invoice.paymentReference(), invoice.issuedAt()) == 1;
    }

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO invoicing.invoice (id, account_id, subscription_id, plan_id, status, amount_minor, currency,
                                           period_start, period_end, payment_reference, issued_at)
            VALUES (:id, :accountId, :subscriptionId, :planId, :status, :amountMinor, :currency,
                    :periodStart, :periodEnd, :paymentReference, :issuedAt)
            ON CONFLICT ON CONSTRAINT invoice_one_per_account_and_period DO NOTHING
            """)
    int insertRow(UUID id, UUID accountId, UUID subscriptionId, String planId, String status, long amountMinor,
            String currency, LocalDate periodStart, LocalDate periodEnd, String paymentReference, Instant issuedAt);

    Optional<Invoice> findByPaymentReference(String paymentReference);

    List<Invoice> findByAccountIdOrderByPeriodStart(UUID accountId);

    List<Invoice> findBySubscriptionIdOrderByPeriodStart(UUID subscriptionId);

    List<Invoice> findByIssuedAtGreaterThanEqualAndIssuedAtLessThan(Instant from, Instant to);
}
