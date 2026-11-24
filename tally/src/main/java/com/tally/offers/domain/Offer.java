package com.tally.offers.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An offer and its published versions (design.md §2). O1: a version is published only for an approved
 * kind. O2: a published version never changes; a change publishes the next version. The version in
 * force on a date ({@link #currentOn(LocalDate)}) is the highest-numbered version whose effectiveFrom
 * is on or before that date; {@link #current()} is the latest published version, even if it is not yet
 * in force, and is not what sign-up should use.
 */
public final class Offer {

    private final String id;
    private final String name;
    private final List<OfferVersion> versions;

    private Offer(String id, String name, List<OfferVersion> versions) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.versions = new ArrayList<>(versions);
        for (int i = 0; i < versions.size(); i++) {
            if (versions.get(i).number() != i + 1) {
                throw new IllegalArgumentException("Offer " + id + " versions must run 1, 2, 3...: " + versions);
            }
        }
    }

    /** A new offer, with nothing published yet. */
    public static Offer create(String id, String name) {
        return new Offer(id, name, List.of());
    }

    /** As stored. */
    public static Offer of(String id, String name, List<OfferVersion> versions) {
        return new Offer(id, name, versions);
    }

    /**
     * Publishes the next version (O2), only for an approved kind (O1), effective from {@code effectiveFrom}.
     * A new version's effectiveFrom must be on or after the previous version's, in publish order; an
     * earlier date is refused and nothing is added.
     */
    public OfferVersion publish(OfferKind kind, OfferTerms terms, Instant now, LocalDate effectiveFrom) {
        if (!kind.approved()) {
            throw new IllegalStateException("Kind " + kind.id() + " is not approved; offer " + id + " cannot use it (O1)");
        }
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        if (!versions.isEmpty() && effectiveFrom.isBefore(versions.getLast().effectiveFrom())) {
            throw new IllegalArgumentException("Effective from " + effectiveFrom
                    + " is before the previous version's " + versions.getLast().effectiveFrom()
                    + "; offer " + id + " cannot take effect before it (ADR-047)");
        }
        OfferVersion version = new OfferVersion(versions.size() + 1, kind.id(), terms, now, effectiveFrom);
        versions.add(version);
        return version;
    }

    /** Publishes the next version effective from the UTC date of {@code now}. */
    public OfferVersion publish(OfferKind kind, OfferTerms terms, Instant now) {
        return publish(kind, terms, now, LocalDate.ofInstant(now, ZoneOffset.UTC));
    }

    /**
     * The version in force on {@code asOf}: the highest-numbered version whose effectiveFrom is on or
     * before asOf. Empty if no version has taken effect yet. Sign-up must use this, not {@link #current()}.
     */
    public Optional<OfferVersion> currentOn(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        OfferVersion inForce = null;
        for (OfferVersion version : versions) {
            if (!version.effectiveFrom().isAfter(asOf)) {
                inForce = version;
            }
        }
        return Optional.ofNullable(inForce);
    }

    /**
     * The latest published version, even if it is not yet in force (ADR-047). This is NOT the version in
     * force; sign-up must use {@link #currentOn(LocalDate)}.
     */
    public Optional<OfferVersion> current() {
        return versions.isEmpty() ? Optional.empty() : Optional.of(versions.getLast());
    }

    public Optional<OfferVersion> version(int number) {
        return number >= 1 && number <= versions.size() ? Optional.of(versions.get(number - 1)) : Optional.empty();
    }

    public List<OfferVersion> versions() {
        return Collections.unmodifiableList(versions);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }
}
