package com.tally.offers.domain;

import java.util.Objects;

/**
 * A kind of offer, such as a fixed price or a free trial (design.md §2, O1). Starts experimental; finance
 * approves it; approval is never withdrawn. Only offers of an approved kind are published.
 */
public final class OfferKind {

    private final String id;
    private KindStatus status;

    private OfferKind(String id, KindStatus status) {
        this.id = Objects.requireNonNull(id, "id");
        this.status = Objects.requireNonNull(status, "status");
    }

    public static OfferKind experimental(String id) {
        return new OfferKind(id, KindStatus.EXPERIMENTAL);
    }

    /** As stored. */
    public static OfferKind of(String id, KindStatus status) {
        return new OfferKind(id, status);
    }

    /** Finance approves the kind. Approving again changes nothing. */
    public void approve() {
        status = KindStatus.APPROVED;
    }

    public boolean approved() {
        return status == KindStatus.APPROVED;
    }

    public String id() {
        return id;
    }

    public KindStatus status() {
        return status;
    }
}
