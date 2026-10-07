# ADR-025. Changes to shared data are additive

**Status:** Accepted

## Context
During a rolling deployment, old and new versions share the database schema, the queue tables and the stored events.

## Decision
- Flyway migrations are expand-only. Renames and drops follow expand–contract across three releases.
- Queue row formats change additively.
- Event rules follow ADR-009.

Business justification: time to market. Deployments never need downtime or coordination.

## Consequences
Breaking changes take three releases instead of one.

## Compliance
Automated, from slice 3: the N−1 compatibility test runs the previous release's image and smoke tests against the newly migrated schema.

## Drivers
D2, D7.

## Revisit when
Never on its own; only if the deployment strategy changes.

## Notes
Split out of ADR-007 in Phase 0.
