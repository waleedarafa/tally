package com.tally.subscription.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** One billing period of a subscription: its number, counting from 1, and {@code [start, end)} in local dates. */
@Embeddable
public record SubscriptionPeriod(
        @Column(nullable = false) int number,
        @Column(name = "start_date", nullable = false) LocalDate start,
        @Column(name = "end_date", nullable = false) LocalDate end) {
}
