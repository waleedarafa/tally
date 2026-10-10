# ADR-004. Billing schedules run as competing consumers on a database queue

**Status:** Superseded by ADR-017

## Context
Millions of per-account billing dates, two instances, and drivers D1, D2 and D4.

## Decision
Billing schedule rows are claimed with `FOR UPDATE SKIP LOCKED`. A reaper recovers stale claims. A unique constraint on (account, billing date) is the final guard. Due times come from the injected clock.

## Consequences
See ADR-017.

## Notes
Superseded during the Step 7 review: ADR-011 introduced scheduled work in Collections, and nothing required it to use the same mechanism. ADR-017 widens the rule to all modules.

Archived; see `docs/architecture/v2/README.md`.
