package com.tally.offers;

import java.util.OptionalInt;
import java.util.UUID;

/** Whether an offer can be sold to an account (design.md §4; R4 as amended in Slice 1R PR-4). */
public interface Offers {

    /**
     * The version in force on the account's local date (ADR-047): the clock instant in the account's
     * time zone, as a {@code LocalDate}, through an offer version's {@code effectiveFrom}. Not the
     * latest published row, even if it is not yet in force. What a new subscription signs up to and
     * keeps (decision A).
     *
     * @throws com.tally.account.UnknownAccountException if there is no such account
     */
    OptionalInt currentVersion(String offerId, UUID accountId);
}
