package com.tally.account.application;

/**
 * Another transaction linked the user to an account first: {@code account_user_one_per_user} refused this link
 * (CLAUDE.md rule 11). Nothing of this transaction may be kept.
 */
public class UserHasAnAccountException extends RuntimeException {

    public UserHasAnAccountException(String user, Throwable cause) {
        super("User " + user + " has an account already", cause);
    }
}
