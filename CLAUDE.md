# Tally: standing instructions for the coding agent

Tally is a subscription billing system built as a learning project, modelled loosely on Kill Bill.
Architecture work happens outside this repository. Your job is to implement it faithfully, and to report back where reality differs from the design.

## Read first
- `docs/architecture/drivers.md`: the drivers, as six-part scenarios. Each has a "Verified by" line.
- `docs/architecture/adr/README.md`: the decision log. Read every record a task touches.
- `docs/briefs/slice-N.md`: the scope of the current slice.

## Stack (ADR-026)
Java 25, Spring Boot 4, Spring Modulith 2, Maven wrapper, PostgreSQL, Flyway, Testcontainers, ArchUnit.
Before adding any other dependency, or if unsure which version is compatible with Spring Boot 4, check the official documentation. Do not guess versions or APIs. If no compatible version exists, stop and report.

## Architecture rules (never break these)
1. **Modules.** One top-level package per bounded context under `com.tally`: `shared`, `account`, `catalog`, `subscription`, `invoicing`, `payment`, `entitlement`, plus `platform` for queue and job infrastructure only (ADR-027). Public API classes sit in the module's base package. Everything else sits in subpackages. Each module declares allowed dependencies in `package-info.java` (ADR-003, ADR-014).
2. **Data.** Each module owns one PostgreSQL schema. No module reads or writes another module's tables. No JPA relations between modules: refer by ID only.
3. **Payment knows nothing about invoices.** Nothing in `payment` imports from `invoicing` (ADR-014).
4. **Time.** Inject `java.time.Clock`. Never call a no-argument `now()` or `System.currentTimeMillis()` outside the clock configuration (D4).
5. **Scheduled work.** Use the shared queue infrastructure: per-module queue tables claimed with `FOR UPDATE SKIP LOCKED`, a reaper, and a business unique constraint. Never use `@Scheduled` directly (ADR-017).
6. **No transaction open during an HTTP call to the provider.** Claim and commit, call, then record the result (ADR-005).
7. **Events.** Additive changes only. Never rename or move an event class. Every event carries an event ID, occurrence time and account ID. Listeners must be idempotent (ADR-009, ADR-022).
8. **Every background job reports health:** last success time and waiting work (ADR-023).
9. **Unknown payment outcomes are never retried** in slice 1. They are reconciled (ADR-018).
10. **Money** is `shared.Money`: amount in minor units (`long`) plus ISO currency. No `double` anywhere near money.

## How to work
- Write the acceptance test for a scenario before its implementation, where practical.
- Run `./mvnw verify` before every commit. It runs unit, integration and fitness-function tests.
- Small commits, each leaving the build green.
- Prefer the simplest code that satisfies the scenario. Do not build for later slices.

## Stop and ask, instead of deciding, when
- A task would change a module's allowed dependencies.
- A task conflicts with an Accepted decision record. Never edit an accepted record. Describe the conflict.
- A library behaves differently from what a decision record assumes (this is what the spikes look for).
- A scenario's response measure cannot be met.

## Report at the end of each slice
Write `docs/briefs/slice-N-report.md` with:
1. What was built, per acceptance test, with pass or fail.
2. Spike findings, with evidence (test names, logs).
3. Every deviation from the brief or the decision records, and why.
4. Open questions and suggested new decision records.
5. Fitness functions added.
