package com.tally.subscription;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A subscription as other modules see it: the account it belongs to, the plan it is on, the offer version it
 * keeps, whether it is ACTIVE, and its current period. Read-only; nothing in another module owns it.
 */
public record SubscriptionSnapshot(UUID id, UUID accountId, String planId, int offerVersion,
        boolean active, int periodNumber, LocalDate periodStart, LocalDate periodEnd) {

    public SubscriptionSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
    }
}
