package com.tally.subscription;

import java.util.UUID;

public class AlreadySubscribedException extends RuntimeException {

    public AlreadySubscribedException(UUID accountId) {
        super("Account " + accountId + " already has a subscription");
    }
}
