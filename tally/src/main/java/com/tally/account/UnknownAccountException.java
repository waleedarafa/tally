package com.tally.account;

import java.util.UUID;

public class UnknownAccountException extends RuntimeException {

    public UnknownAccountException(UUID accountId) {
        super("No account " + accountId);
    }
}
