package com.tally.collections;

import java.util.Optional;
import java.util.UUID;

/** Collections' public API (design.md §4). */
public interface Collections {

    /** The account's case: the open one, or else the latest. Empty if no payment was ever declined. */
    Optional<CaseView> caseFor(UUID accountId);

    /**
     * "Pay now" (Slice 3, decision 2): asks Invoicing again for every unpaid invoice of the account's case, in any state
     * with a debt open (OPEN, OVERDUE, BLOCKED, WRITTEN_OFF), with the case's next retry number. Opens no transaction
     * of its own toward the provider (rule 6): it records the retry and puts it on Collections' retry queue, due now,
     * in the caller's transaction; the retry asks after the commit. Empty if the account has no debt open. A decline
     * changes no state: the timers decide.
     */
    Optional<PaymentRequestView> requestPayment(UUID accountId);
}
