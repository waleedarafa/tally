package com.tally.account.application;

import com.tally.account.AccountView;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Optional;

/**
 * Each user's one account (ADR-032; WAR-84), for {@code account}'s own web layer: the link lives in this module, and
 * no other module reads it.
 */
public interface AccountUsers {

    /** The account, and whether this call opened it. */
    record Opened(AccountView account, boolean opened) {
    }

    /**
     * The user's account; opened and linked to the user, in one transaction, if the user has none.
     *
     * @throws UserHasAnAccountException if another transaction linked the user first: the link's unique constraint
     *         refused this one, and the caller's transaction must roll back; {@link #accountOf} then gives the account
     */
    Opened openFor(String user, Currency currency, ZoneId timeZone);

    Opened openFor(String user, Currency currency, ZoneId timeZone, String country);

    Optional<AccountView> accountOf(String user);
}
