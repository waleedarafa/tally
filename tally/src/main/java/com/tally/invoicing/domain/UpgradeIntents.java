package com.tally.invoicing.domain;

import java.time.Instant;
import java.util.Optional;

/** Invoicing's mid-period upgrade intents (a port; {@code adapter.persistence} implements it, F9). */
public interface UpgradeIntents {

    /** Saves {@code intent} if no row for its payment reference exists yet (D1). */
    void save(UpgradeIntent intent);

    Optional<UpgradeIntent> findByReference(String paymentReference);

    /** Marks the intent completed. A second call for an already-completed intent changes nothing. */
    void markCompleted(String paymentReference, Instant completedAt);
}
