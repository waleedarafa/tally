package com.tally.invoicing.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Invoicing's mid-period upgrade intents (a port; {@code adapter.persistence} implements it, F9). */
public interface UpgradeIntents {

    /**
     * Adds the intent. Returns false and changes nothing if an intent with the same payment reference exists,
     * or another open intent exists for the same period invoice (index upgrade_intent_one_open_per_period_invoice).
     * Any other failure is thrown (CLAUDE.md rule 11).
     */
    boolean place(UpgradeIntent intent);

    Optional<UpgradeIntent> forReference(String paymentReference);

    /** Marks the intent completed. A second call for an already-completed intent changes nothing. */
    void markCompleted(String paymentReference, Instant completedAt);

    /**
     * Closes an open intent that will not complete. Changes nothing if it is already completed or closed.
     */
    void close(String paymentReference, UpgradeIntent.Closure closure, Instant closedAt);

    /** The intent for {@code periodInvoiceId} that is not completed and not closed, if any. */
    Optional<UpgradeIntent> openFor(UUID periodInvoiceId);
}
