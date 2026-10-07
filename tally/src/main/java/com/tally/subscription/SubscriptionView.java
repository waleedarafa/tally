package com.tally.subscription;

import java.time.Instant;
import java.util.UUID;

public record SubscriptionView(UUID id, UUID accountId, String planId, Instant startedAt, int version) {
}
