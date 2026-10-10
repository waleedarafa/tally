package com.tally.invoicing.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Invoicing's invoices (a port; {@code adapter.persistence} implements it, F9). */
public interface Invoices {

    /**
     * Adds {@code invoice} unless its subscription already has an invoice for that period (I2, D1),
     * atomically: a concurrent caller waits for the first insert to commit, then adds nothing (FR-028).
     * Returns whether this call added it.
     */
    boolean addIfAbsent(Invoice invoice);

    Optional<Invoice> forPaymentReference(String paymentReference);

    /** Oldest period first. */
    List<Invoice> ofAccount(UUID accountId);

    /** Oldest period first. */
    List<Invoice> ofSubscription(UUID subscriptionId);

    /** Issued in {@code [from, to)}. */
    List<Invoice> issuedBetween(Instant from, Instant to);
}
