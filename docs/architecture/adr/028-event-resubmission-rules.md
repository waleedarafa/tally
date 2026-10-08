# ADR-028. Event resubmission rules

**Status:** Accepted. Extends ADR-022.

## Context
Spike A (slice 1) tested Spring Modulith 2.1.1. ADR-022's assumptions held, with four refinements:
- `resubmitIncompletePublications(ResubmissionOptions)` resubmits only `FAILED` publications. A publication left `PUBLISHED` or `PROCESSING` by a crash is never picked up by it.
- Two instances resubmitting at once ran the same publication twice (9 and 12 of 20 in two runs).
- Resubmission cannot tell whether a publication is still running on another instance. An old enough publication is resubmitted while it is still in progress, so it runs twice at the same time.
- `republish-outstanding-events-on-restart=true` re-runs work that other instances are still doing.

## Decision
- The resubmission job uses only the "older than" API (or its predicate variant).
- The job runs through the durable queue (ADR-017), so one instance resubmits at a time.
- The minimum age stays well above any listener's run time. Production value: 5 minutes.
- `republish-outstanding-events-on-restart` stays off.
- Every listener is safe when duplicates arrive **at the same time**, not only one after another. Safety comes from the database: a unique constraint, an atomic insert, or an optimistic lock. "Check, then act" is never enough.
- An alert fires when `tally.events.oldest_incomplete_age_seconds` exceeds 15 minutes.

Business justification: cost. A paid invoice must never be processed twice, and a customer who paid must never be chased.

## Consequences
- Every new listener needs a concurrency guard, not only an idempotency check.
- A Modulith upgrade can change this behaviour silently, unless the spike tests catch it.

## Compliance
Automated. `SpikeAEventPublicationIT` pins the observed library behaviour. `ConcurrentDeliveryIT` shows the invoice listener failing without its optimistic lock. Slice 2 adds a fitness function that delivers every pinned event twice at the same time to all its listeners, then runs the invariant checks.

## Drivers
D1, D2, D3, D7.

## Revisit when
Spring Modulith changes its resubmission or locking behaviour.

## Notes
Recorded after slice 1, from Spike A. Implemented in `platform.internal.EventResubmission`.
