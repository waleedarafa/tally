package com.tally.subscription;

import java.time.Instant;

public record TimelineEntry(int version, SubscriptionChangeType type, String planId, Instant occurredAt) {
}
