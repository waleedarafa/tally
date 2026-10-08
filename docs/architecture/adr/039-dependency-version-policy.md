# ADR-039. Dependency version policy

**Status:** Accepted. Supersedes ADR-026.

## Context
ADR-026 said every version is pinned through Spring Boot's dependency management. Spring Boot does not manage Resilience4j or ArchUnit.

## Decision
- Java 25, Spring Boot 4.1, Spring Modulith 2.1, Maven wrapper, PostgreSQL.
- Versions come from Spring Boot's dependency management where Boot manages them.
- Otherwise, a version is pinned once, in the parent `pom.xml`, with a comment naming why. Example: Resilience4j 2.4.0 core modules, because its Boot starter targets Boot 4.0.
- A library may come in through another managed library when that is the version it was built against. Example: ArchUnit 1.4.2 through `spring-modulith-starter-test`.
- No version is chosen without checking compatibility in official documentation.

Business justification: cost. Builds stay repeatable.

## Consequences
An upgrade of Spring Modulith may change ArchUnit. The fitness tests fail loudly if it breaks them.

## Compliance
Build configuration. Review of every new version line in the parent `pom.xml`.

## Drivers
Constraint.

## Revisit when
A major version reaches the end of open-source support.

## Notes
Recorded after slice 1 (deviations D1, D2).
