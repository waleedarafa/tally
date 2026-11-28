package com.tally.offers.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** A published version of an offer: a value that never changes (O2). */
public record OfferVersion(int number, String kind, OfferTerms terms, Instant publishedAt, LocalDate effectiveFrom) {

    public OfferVersion {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        if (number < 1) {
            throw new IllegalArgumentException("Versions are numbered from 1: " + number);
        }
    }
}
