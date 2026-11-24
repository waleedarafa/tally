# ADR-047. An offer version takes effect on a date

**Status:** Accepted, 24 November 2026 (WAR-65; taken by delegation).

## Context

A-06 / WAR-65: marketing publishes a new price on 20 October that should apply from 1 November. Today a version applies from the moment it is published. `Offer.current()` is the latest published row, and `OfferVersion` carries only `publishedAt`.

New subscribers should get the version in force at sign-up. Existing subscribers already keep the version they signed up to (Slice 1R PR-5, which sharpens O3). Moving them is a later, explicit, dated change. Kill Bill pins a catalogue version on the subscription the same way.

The Design Record snapshot is not edited.

## Decision

Each offer version has an `effectiveFrom` local date, set when it is published and never changed (O2). `publishedAt` remains the instant of publication.

The version in force on a date D is the highest-numbered version whose `effectiveFrom` is on or before D. If no version has taken effect yet, there is no current version.

A new version's `effectiveFrom` must be on or after the previous version's (publish order). Two versions may share a date: the later number is the one in force from that date, so a not-yet-effective price can be replaced without editing the earlier row.

For a new sign-up, "today" is the account's local date: the clock instant in the account's time zone. That is the same rule used for the billing day (ADR-043). `Offers.currentVersion(offerId, accountId)` keeps its meaning and uses that date. Pricing a subscription still uses the version on the request (the pinned one). An upgrade already asks `currentVersion` for the new plan, so it also gets the version in force on the account's local date.

Existing rows are backfilled with the UTC date of `published_at`. There is no catalogue time zone; UTC is the only honest date of an instant.

## Consequences

- Publishing on 20 October with `effectiveFrom` 1 November leaves the previous version current through 31 October.
- A subscriber who signed up on 20 October keeps their pinned version after 1 November.
- A subscriber who signs up on 1 November gets the new version.
- A Dubai account and a UTC account may switch on slightly different instants around midnight, which is accepted for a date-based catalogue.
