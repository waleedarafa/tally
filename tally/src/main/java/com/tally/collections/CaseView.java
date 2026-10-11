package com.tally.collections;

import java.time.Instant;
import java.util.UUID;

/** A Collections case, for reading. {@code closedAt} is null while it is open. */
public record CaseView(UUID caseId, UUID accountId, UUID invoiceId, CaseStatus status, Instant openedAt,
        Instant closedAt, int retries) {
}
