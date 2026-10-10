# Tally: standing instructions for the coding agent

Tally is a subscription billing system built as a learning project, modelled loosely on Kill Bill.
Architecture work happens outside this repository. Your job is to implement it faithfully, and to report back where reality differs from the design.

Decision records are cited as `ADR-NNN` for the second design (`docs/architecture/v2/README.md`) and `FR-NNN` for the archived first run (`docs/architecture/archive/first-run/`). The two logs reuse numbers for different decisions, so always use the prefix. Slice 1 briefs and reports were written before the archive and use ADR-NNN for first-run records.

## Read first
- `docs/architecture/v2/README.md`: the decision log of the second design, and what happened to every first-run record.
- `docs/architecture/v2/drivers.md`: the drivers. Numbering differs from the first run.
- `docs/architecture/v2/design.md`: contexts, invariants, the dependency graph, public APIs and events, state machines, Money, the REST contract.
- `docs/briefs/slice-N.md`: the scope of the current slice.

## Stack (FR-039)
Java 25, Spring Boot 4, Spring Modulith 2, Maven wrapper, PostgreSQL, Flyway, Testcontainers, ArchUnit.
Before adding any other dependency, or if unsure which version is compatible with Spring Boot 4, check the official documentation. Do not guess versions or APIs. If no compatible version exists, stop and report.

## Architecture rules (never break these)
1. **Modules.** One top-level package per context under `com.tally`: `shared`, `platform`, `account`, `offers`, `subscription`, `invoicing`, `payment`, `collections`, `access`. `platform` holds queue and job infrastructure only (FR-027). The dependency graph is `design.md` §3, pinned in `architecture/module-dependencies.txt`; never widen it without approval (FR-040). Public API classes sit in the module's base package. Everything else sits in subpackages. Each module declares allowed dependencies in `package-info.java` (FR-003, Design Record §10). Until Slice 1R PR-10, PinnedModuleDependenciesTest checks module-dependencies-current.txt. When a PR moves a module, edit that file, not the target.
2. **Data.** Each module owns one PostgreSQL schema. No module reads or writes another module's tables. No JPA relations between modules: refer by ID only.
3. **Payment knows nothing about invoices.** Nothing in `payment` imports from `invoicing` (R1).
4. **Time.** Inject `java.time.Clock`. Never call a no-argument `now()` or `System.currentTimeMillis()` outside the clock configuration.
5. **Scheduled work.** Use the shared queue infrastructure: per-module queue tables claimed with `FOR UPDATE SKIP LOCKED`, a reaper, and a business unique constraint. Never use `@Scheduled` directly (FR-017, ADR-003).
6. **No transaction open during an HTTP call to the provider.** Write the attempt and its key, commit, call, then record the result (FR-005, ADR-004).
7. **Events.** Additive changes only. Never rename or move an event class. Every event carries an event ID, occurrence time and account ID. Consumers must handle enum values they do not know. Listeners must be safe when duplicates arrive **at the same time**: use a unique constraint, an atomic insert or an optimistic lock, never "check, then act" (FR-009, FR-028, ADR-016). Resubmission handles one publication at a time; a failure is isolated (ADR-016).
8. **Every background job reports health:** last success time and waiting work (FR-023).
9. **Payments.** Payment outcomes are succeeded, declined, technical failure or unknown. Only a decline starts Collections. Unknown is resolved by key, never retried. A requested or sent attempt older than the timeout is unknown (ADR-013, ADR-018).
10. **Money** is `shared.Money`: amount in minor units (`long`) plus ISO currency. No `double` anywhere near money. `Money.times(Ratio)` is the only place rounding happens: half up. Use `allocate` to split (ADR-017).
11. **Duplicate protection lives in business tables.** A queue key is never the only guard: queue rows are deleted after the retention period (FR-037).
12. **The fake provider is a model of a real one.** Any new behaviour that depends on how a provider acts must be listed in `fake-provider/CONTRACT.md`.
13. **Idempotency over REST.** Every request that changes state over REST requires an `Idempotency-Key` (ADR-019).
14. **Inside layout.** Inside `offers`, `subscription`, `invoicing` and `payment`, the domain package imports no Spring and no adapter; `jakarta.persistence` annotations are allowed (Design Record §10 C). Port methods use business names (`dueForReconciliation(now, limit)`, `issuedBetween(from, to)`, `add(subscription)`). Spring Data names stay in `adapter.persistence`, behind a default method, as `JpaInvoices.insertIfAbsent` does.

## How to work
- `main` is the trunk. Work on a short-lived branch and merge through a pull request (FR-038, ADR-008).
- Write the acceptance test for a scenario before its implementation, where practical.
- Run `./mvnw verify` before every commit. It runs unit, integration and fitness-function tests.
- Small commits, each leaving the build green.
- Prefer the simplest code that satisfies the scenario. Do not build for later slices.

## Stop and ask, instead of deciding, when
- A task would change a module's allowed dependencies.
- A task conflicts with an Accepted decision record. Never edit an accepted record's decision text. Describe the conflict.
- A library behaves differently from what a decision record assumes (this is what the spikes look for).
- A scenario's response measure cannot be met.

## Report at the end of each slice
Write `docs/briefs/slice-N-report.md` with:
1. What was built, per acceptance test, with pass or fail.
2. Spike findings, with evidence (test names, logs).
3. Every deviation from the brief or the decision records, and why.
4. Open questions and suggested new decision records.
5. Fitness functions added.
6. An audit of every decision record the slice touched: does its Compliance section describe a check that exists in the code? List each gap. Never claim a check that is only planned.
