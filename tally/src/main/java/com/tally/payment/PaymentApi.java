package com.tally.payment;

import com.tally.shared.Money;
import java.util.Optional;
import java.util.UUID;

/**
 * Collects money. Knows nothing about invoices: it takes an amount and an opaque reference, and
 * reports results by reference (ADR-014).
 */
public interface PaymentApi {

    /**
     * Asks for an amount to be collected. The attempt is made asynchronously by a payment worker
     * (ADR-005). Idempotent per reference: a second request for a reference changes nothing.
     */
    void requestCollection(String reference, UUID accountId, Money amount, String paymentToken);

    /** The latest attempt for a reference, read from Payment (read-your-writes, ADR-019). */
    Optional<PaymentStatus> status(String reference);
}
