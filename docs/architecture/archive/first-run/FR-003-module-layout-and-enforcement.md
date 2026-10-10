# ADR-003. Module layout and enforcement

**Status:** Accepted

## Context
Tally has one core module, several supporting modules of very different complexity, and one team. A modular monolith decays unless its boundaries are enforced automatically.

## Decision
We will use Spring Modulith modules, one per bounded context, each with its own PostgreSQL schema.
- Complex modules (`catalog`, `invoicing`, `payment`) use a hexagonal layout with a rich domain model.
- Simple modules (`account`, `entitlement`) use plain layered code.
- `subscription` uses a light domain model.
- Package names are the same in every module.
- Only names and explicit types cross module boundaries. Statuses are enums.
- Aggregates refer to other modules' aggregates by identifier only.

Business justification: time to market. Boundaries that cannot erode keep each change inside one module.

## Consequences
- Boundaries cannot erode silently.
- Two layouts to learn.
- Extraction later is a schema and package move, not a data untangling job.

## Compliance
Automated, on every build:
1. Spring Modulith verification: no cycles, no access to internal packages, only declared dependencies.
2. ArchUnit: no `@ManyToOne`, `@OneToMany` or `@OneToOne` to another module's entity.
3. ArchUnit: no direct `now()` calls outside the clock configuration (D4).
4. ArchUnit: `domain` packages do not depend on web or adapter packages.

## Drivers
D4, D5, D8, D9.

## Revisit when
Module boundaries stop moving. Then consider one Maven module per bounded context, so the compiler also enforces them.

## Notes
Decided in Step 3, Part B. JPA annotations on aggregates are allowed: a separate persistence model does not pay back for one team.

Archived; see `docs/architecture/v2/README.md`.
