package com.tally.offers.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An offer and its published versions (design.md §2). O1: a version is published only for an approved
 * kind. O2: a published version never changes; a change publishes the next version. The current version
 * is the latest one.
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

    /** Publishes the next version (O2), only for an approved kind (O1). */
    public OfferVersion publish(OfferKind kind, OfferTerms terms, Instant now) {
        if (!kind.approved()) {
            throw new IllegalStateException("Kind " + kind.id() + " is not approved; offer " + id + " cannot use it (O1)");
        }
        OfferVersion version = new OfferVersion(versions.size() + 1, kind.id(), terms, now);
        versions.add(version);
        return version;
    }

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
