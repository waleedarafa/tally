package com.tally.offers;

import java.time.LocalDate;
import java.util.Objects;

/** A subscription's billing period: its number, counting from 1, and {@code [start, end)}. */
public record Period(int number, LocalDate start, LocalDate end) {

    public Period {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (number < 1) {
            throw new IllegalArgumentException("Periods are numbered from 1: " + number);
        }
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("A period ends after it starts: " + start + " to " + end);
        }
    }
}
