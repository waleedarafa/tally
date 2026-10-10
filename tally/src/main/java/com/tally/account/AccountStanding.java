package com.tally.account;

import java.util.UUID;

/**
 * Sets an account's standing (design.md §3, §4). Exposed to {@code collections} only: F3
 * ({@code F3StandingTest}) fails the build if any other module depends on it. Slice 1R adds the API and
 * the rule; Collections first changes standing in a later slice (overdue on day 7, blocked on day 10).
 */
public interface AccountStanding {

    /**
     * @param cause why, for the account's history, for example the Collections case
     * @throws UnknownAccountException if there is no such account
     */
    void set(UUID accountId, Standing standing, String cause);
}
