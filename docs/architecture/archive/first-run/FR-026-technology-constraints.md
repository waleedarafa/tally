# ADR-026. Technology constraints

**Status:** Superseded by ADR-039

## Context
Every other record assumes a stack. Recording it makes the assumption visible.

## Decision
Java 25 (current LTS), Spring Boot 4, Spring Modulith 2, Maven with the wrapper, PostgreSQL. Dependency versions are pinned through Spring Boot's dependency management.

Business justification: cost and learning. Kill Bill and Richardson's examples are Java, which makes comparison easy.

## Consequences
If the organisation's main stack differs, less of the code transfers to daily work.

## Compliance
Build configuration.

## Drivers
Constraint.

## Revisit when
A major version reaches end of open-source support.

## Notes
Recorded retroactively in Phase 0; chosen before Step 1.

Archived; see `docs/architecture/v2/README.md`.
