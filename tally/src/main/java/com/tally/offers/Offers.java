package com.tally.offers;

import java.util.OptionalInt;
import java.util.UUID;

/** Whether an offer can be sold to an account (design.md §4; R4 as amended in Slice 1R PR-4). */
public interface Offers {

    /**
     * The offer's current version, if it can be sold to the account: what a new subscription signs up to
     * and keeps (decision A).
     *
     * @throws com.tally.account.UnknownAccountException if there is no such account
     */
    OptionalInt currentVersion(String offerId, UUID accountId);
}
