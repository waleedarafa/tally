# ADR-017. All durable scheduled work runs as competing consumers on per-module queue tables

**Status:** Accepted, supersedes ADR-004

## Context
Billing schedules, payment attempts, dunning steps and event resubmission all need work that happens at a time, survives crashes, and runs exactly once across two instances.

## Decision
Every module that needs scheduled or durable work keeps its own queue table. Every instance polls and claims due rows with `FOR UPDATE SKIP LOCKED`. A reaper returns stale claims. A business unique constraint is the final guard against duplicates. "Due" means `due_at <= clock.now()`, using the injected clock.

Business justification: cost. No scheduler infrastructure, and correctness by construction.

## Consequences
- No leader. Both instances share the load.
- Every instance polls the database.
- Claiming and reaping logic must be tested carefully.

## Compliance
Automated. ArchUnit forbids `@Scheduled` outside the shared queue infrastructure. Chaos test kills an instance mid-run. Every queue reports health (ADR-023).

## Drivers
D1, D2, D4, D7, D13.

## Revisit when
Polling load on the database becomes visible in its metrics.

## Notes
Created in the Step 7 review.
