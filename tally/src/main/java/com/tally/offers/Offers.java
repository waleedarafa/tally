package com.tally.offers;

import java.util.UUID;

/** Whether an offer can be sold to an account (design.md §4; R4 as amended in Slice 1R PR-4). */
public interface Offers {

    /**
     * True if the offer exists and has a price in the account's currency.
     *
     * @throws com.tally.account.UnknownAccountException if there is no such account
     */
    boolean isOffered(String offerId, UUID accountId);
}
