package com.tally.payment;

import com.tally.shared.Money;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Collects money. Knows nothing about invoices: it takes an amount and an opaque reference, and
 * reports results by reference (FR-014).
 */
public interface PaymentApi {

    /**
     * Asks for an amount to be collected. The attempt is made asynchronously by a payment worker
     * (FR-005). Idempotent per reference: a second request for a reference changes nothing.
     */
    void requestCollection(String reference, UUID accountId, Money amount, String paymentToken);

    /** The latest attempt for a reference, read from Payment (read-your-writes, FR-019). */
    Optional<PaymentStatus> status(String reference);

    /**
     * The amount collected for each of these references: references with a SUCCEEDED attempt only.
     * Used to check what was collected against what was invoiced (FR-024).
     */
    Map<String, Money> collected(Collection<String> references);
}
