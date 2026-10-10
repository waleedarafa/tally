package com.tally.offers.domain;

import java.time.Instant;
import java.util.Objects;

/** A published version of an offer: a value that never changes (O2). */
public record OfferVersion(int number, String kind, OfferTerms terms, Instant publishedAt) {

    public OfferVersion {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(publishedAt, "publishedAt");
        if (number < 1) {
            throw new IllegalArgumentException("Versions are numbered from 1: " + number);
        }
    }
}
