package com.tally.subscription.domain;

import java.util.UUID;

/** Subscription's subscriptions (a port; {@code adapter.persistence} implements it, F9). */
public interface Subscriptions {

    boolean existsByAccountId(UUID accountId);

    Subscription saveAndFlush(Subscription subscription);
}
