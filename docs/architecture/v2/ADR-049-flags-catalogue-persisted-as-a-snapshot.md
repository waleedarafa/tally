# ADR-049. The flags catalogue is persisted as a snapshot

**Status:** Accepted, 25 November 2026 (WAR-66; taken by delegation).

## Context

V56 stores each flag's current state (`default_on`, `released_at`, account and country assignments) and a change log (`flags.flag_change`). The log entries are human descriptions ("account {id} set to off", "fully released"), not structured events, so they cannot be replayed to rebuild state.

`Flags` is a mutable in-memory aggregate. `define` always starts a flag off and appends a define entry, so it cannot reload a flag whose default is on, that has been released, or that already has a history. PricingService (WAR-66 step 4) also needs to list every defined flag when it records flag state on a quote.

F9 requires repositories to be plain ports in the domain, implemented in `adapter.persistence`. F8 already says `flags` has a domain plus a persistence adapter and no application package. The Design Record snapshot is not edited.

## Decision

The catalogue is persisted as a snapshot, not by replaying the change log. The port is `FlagCatalogue` in `com.tally.flags.domain`, with `Flags load()` and `void save(Flags flags)`.

`save` replaces the stored catalogue in one transaction: it deletes every row in `flags.flag` (assignments and changes cascade) and inserts the snapshot. The catalogue is small, so a full replace is enough. `flag_change.seq` is the 1-based index of `Flags.changes(name)` in list order.

`Flags` gains `names()` (sorted), accessors for the default, the release instant and the assignments, and `loadDefined(...)`, which stores the supplied snapshot as-is: it does not replay the log and it does not append a define entry.

## Consequences

- JdbcFlags can save and load without inventing a second model of flag state.
- The change log remains an audit trail, not the source of truth for current state.
- Step 4 can load the catalogue and iterate `names()`.
- There is still no application package in `flags`.

## Rejected

- Replaying the change-log descriptions as events: the strings are not a stable event schema.
- Incremental upserts without a full replace: more code for a small catalogue.
- Putting the port in `com.tally.flags` (the module root): other modules put ports in `domain` (`OfferCatalogue`, `UpgradeIntents`), and F9 requires that.
