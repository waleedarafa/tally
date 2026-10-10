# ADR-001. Tally is a modular monolith

**Status:** Accepted

## Context
Tally has one team. Three high-value, high-risk drivers (D1, D2, D3) need the save of business data and the publishing of its event to succeed or fail together. The bounded contexts talk moderately: a renewal crosses five contexts, but each hop is one business event. Payment differs from the rest, because of provider outages and rate limits, so it may need extraction later.

Options considered: layered monolith, modular monolith, modular monolith with runtime plugins (Kill Bill's shape), service-based architecture, microservices.

## Decision
We will build Tally as a modular monolith on Spring Modulith, with one PostgreSQL database and one schema per module. Swappable parts use Java interfaces (`PaymentGateway`, `TaxCalculator`). The build fails if a module boundary is broken.

Business justification: time to market and cost. One team ships faster without the network, deployment and consistency work that a distributed style needs. With one team, those costs would buy no team independence.

## Consequences
- Crash safety and exactly-once processing stay simple, because one transaction covers each change and its event.
- All modules deploy together. One release carries every change. Acceptable for one team; a cost with several (see ADR-008).
- Payment is designed so it can be extracted.

## Compliance
Automated. `ModuleStructureTest` runs Spring Modulith verification on every build. ArchUnit forbids JPA relations between modules (ADR-003).

## Drivers
D1, D2, D3, D5. Constraint: one team.

## Revisit when
- A second team takes ownership of Payment and needs its own release cycle.
- Payment workers need to scale separately from the rest.
- Tally starts handling raw card numbers.

If a trigger fires, prefer a service-based split along team boundaries (a few coarse domain services) before considering microservices.

## Notes
Proposed in Step 2. Revised after the context map: Pricing merged into Catalog and Pricing; Account, Entitlement and Collections added. Accepted after domain discovery. Service-based target added in the Step 2 backfill.

Archived; see `docs/architecture/v2/README.md`.
