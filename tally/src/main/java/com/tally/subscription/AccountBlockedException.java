package com.tally.subscription;

import java.util.UUID;

/** The account's standing is BLOCKED: it may not subscribe (design.md §8, {@code account-blocked}). */
public class AccountBlockedException extends RuntimeException {

    public AccountBlockedException(UUID accountId) {
        super("Account " + accountId + " is blocked");
    }
}
