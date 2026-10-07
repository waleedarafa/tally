# ADR-027. A platform module holds queue and job infrastructure

**Status:** Accepted

## Context
ADR-017 requires every module's durable work to use competing consumers on queue tables, and forbids `@Scheduled`. Periodic jobs (event resubmission, reconciliation, invariant checks) also need to run exactly once across instances. Building this machinery in every module would duplicate subtle code.

## Decision
A `platform` module provides `DurableQueue`, the reaper, self-rescheduling periodic jobs, pollers and health metrics. It contains infrastructure only, never business rules or business data. Each business module keeps its own queue tables in its own schema and registers its own handlers. Every module may depend on `platform`; `platform` depends on no business module.

Business justification: cost. Subtle concurrency code is written and tested once.

## Consequences
- One place owns threads for background work.
- `platform` must stay small. Business logic leaking into it would recreate SOA's shared-service coupling.

## Compliance
Automated. Spring Modulith verification: `platform` depends on no business module. ArchUnit: `@Scheduled` and executor creation only inside `platform`.

## Drivers
D1, D2, D7, D13.

## Revisit when
A module needs queue behaviour the shared mechanism cannot express.

## Notes
Created while writing the slice 1 brief (Phase 0).
