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

    /**
     * Collections' retry {@code retry} (1 on day 3) of a declined reference (Slice 1R PR-8). First asks the
     * provider for the reference (ADR-026): if it shows any charge, no new attempt is made, and
     * the decline is corrected to SUCCEEDED. Otherwise a new attempt, with its own key, only while none is
     * REQUESTED, SENT or UNKNOWN and none SUCCEEDED. Does nothing for an unknown reference, or if retry
     * {@code retry} was already asked for: a retry asked for twice is made once.
     *
     * <p>Calls the provider, so it must not be called inside a transaction (CLAUDE.md rule 6).
     *
     * @throws IllegalStateException inside a transaction
     */
    void collectAgain(String reference, int retry);

    /** The latest attempt for a reference, read from Payment (read-your-writes, FR-019). */
    Optional<PaymentStatus> status(String reference);

    /**
     * The amount collected for each of these references: references with a SUCCEEDED attempt only.
     * Used to check what was collected against what was invoiced (FR-024).
     */
    Map<String, Money> collected(Collection<String> references);
}
