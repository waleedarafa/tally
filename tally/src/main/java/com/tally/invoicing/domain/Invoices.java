package com.tally.invoicing.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Invoicing's invoices (a port; {@code adapter.persistence} implements it, F9). */
public interface Invoices {

    /**
     * Inserts {@code invoice} unless the account already has one for its billing date (D1), atomically:
     * a concurrent caller waits for the first insert to commit, then inserts nothing (FR-028).
     * Returns whether this call inserted it.
     */
    boolean insertIfAbsent(Invoice invoice);

    Optional<Invoice> findByPaymentReference(String paymentReference);

    List<Invoice> findByAccountIdOrderByPeriodStart(UUID accountId);

    List<Invoice> findBySubscriptionIdOrderByPeriodStart(UUID subscriptionId);

    List<Invoice> findByIssuedAtGreaterThanEqualAndIssuedAtLessThan(Instant from, Instant to);
}
