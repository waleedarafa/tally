# ADR-010. No message broker until an extraction is accepted

**Status:** Accepted

## Context
All event consumers run inside one process. A broker would add a system to operate with no consumer outside the process. Kill Bill runs permanently without a broker.

## Decision
Spring Modulith's event publication registry is the outbox. We will add a message broker only when an extraction is accepted (ADR-001), and only for events that must leave the process. When it arrives, the partition key is the account ID.

Business justification: cost. One less system to run.

## Consequences
- No ordering between parallel listeners. Ordering is handled by re-reading current state and by version numbers.
- Delivery is at least once. Handlers must be idempotent (ADR-022, Step 6).

## Compliance
Manual review: no broker dependency in the build until an extraction record exists.

## Drivers
D2.

## Revisit when
An extraction is accepted.

## Notes
Decided in Step 6. Trigger revised in the Step 7 review: it previously said "at slice 7", which contradicted ADR-001.
