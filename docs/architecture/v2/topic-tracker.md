# Topic tracker

One row per architecture topic from the course review, checked against this repository on 2026-10-13.
Each row says what the topic means in Tally, its status, and the evidence for that status.
`topic-tracker.csv` holds the same rows, for spreadsheets and other tools.

The Design Record decides. Where a row disagrees with it, the row says so and the Design Record's choice stands.
Drivers are the D-numbers of [`drivers.md`](drivers.md). The course review used its own scenarios, S1 to S7; this tracker does not.

## Statuses

| Status | Meaning |
|---|---|
| Not started | Nothing designed or built. |
| Designed | Decided in the Design Record or a decision record. No code yet. |
| Partly built | Some of it runs. The note names what is missing. |
| Built | It runs in the code, or, for a design activity, its output is in the Design Record. |
| Checked | It runs, and a test or fitness function fails the build if it breaks. |
| Not applicable | The topic assumes something Tally is not. The note says what. |

"Was" is the status the course table gave before this check.

## Summary

| Level | Topics | Not started | Designed | Partly built | Built | Checked | Not applicable |
|---|---|---|---|---|---|---|---|
| 0 Decision drivers | 9 |  |  | 2 | 7 |  |  |
| A Domain | 15 | 1 |  | 3 | 7 | 4 |  |
| B Architecture styles | 8 |  | 1 | 1 | 4 | 1 | 1 |
| C Inside a module | 6 | 1 |  | 2 |  | 3 |  |
| D Data and reliability | 24 | 4 | 4 | 4 | 5 | 6 | 1 |
| D2 Distributed coordination | 11 | 3 | 4 | 1 | 3 |  |  |
| E Evolution and governance | 15 | 3 | 3 | 1 | 4 | 3 | 1 |
| F Delivery | 17 | 4 | 5 | 5 | 3 |  |  |
| G Teams | 8 | 3 | 3 |  | 1 |  | 1 |
| H Runtime infrastructure | 11 | 4 | 4 | 2 |  | 1 |  |
| I Security | 13 | 8 | 1 | 1 | 1 |  | 2 |
| **All** | **137** | **31** | **25** | **22** | **35** | **18** | **6** |

Before this check the course table had 24 topics built or checked. After it, 53. 77 of 137 statuses changed.

## What the check found

**1. The course table under-reported the work.** Most of what it marked "not started" in decision drivers, domain, styles and teams is already in the Design Record: the drivers (Step 8), discovery (Steps 3 and 4), the Core Domain Chart and Wardley map (Steps 2 and 5), ADR-001 with its scoring (Step 9), the team design (Step 11) and a lightweight evaluation (Step 15). The code also had more than the table knew: optimistic locks, named business constraints, the injected clock with its fitness test, the circuit breaker and rate limiter, the two-stage pipeline with one image per commit, and Prometheus metrics.

**2. Four pieces of course advice conflict with the Design Record.** The Design Record stands. Each needs a new decision record only if the advice is wanted.

| Topic | Course advice | Design Record |
|---|---|---|
| 0-02 Top three | Data integrity, modifiability, deployability | Correctness, recoverability, deployability (Step 8) |
| G-02 Team design | Four or two stream-aligned teams | One team of about six now; a planned split into Subscriber and Revenue teams, with triggers (Step 11) |
| D-09 Upgrade | Unlock the new plan only after the charge | The upgrade story gives access at once (Step 3); Slice 1R gives access at sign-up. ADR-025 (proposed) covers first-invoice declines |
| F-02 Flags | OpenFeature with a database provider | ADR-010: a billing table, per account or country, recorded in the PriceQuote reason; hosted percentage flags rejected |

**3. Six topics assume something Tally is not.** Tally bills households for one streaming business (Design Record §1). It has no merchants, no tenants and no usage billing, and it asks the provider by reference instead of receiving webhooks. B-07, D-16, E-08, G-03, I-08 and I-09 are marked not applicable. I-02, I-04 and I-05 were reworded: the callers are the subscriber app and the back office.

**4. Two checks the Design Record describes do not exist.** F5 (an entity maps only to its module's schema) and F6 (no foreign key crosses schemas), Design Record §10, have no test. `TallyApplicationIT` checks only that the schemas exist. D-01 stays at Built, not Checked.

**5. The largest open gaps.** These are high priority and not yet built or checked:

- A-06 Effective-dated catalogue: partly built. Versions are published and pinned per subscriber. No future effective date: a version applies from publication.
- B-01 Architecture quantum: partly built. Access is a separate quantum on paper (ADR-001) and in-process today. Extraction is Slice 2.
- B-08 Access with its own database: designed. ADR-001; ADR-007 (Access has its own small PostgreSQL)
- D-07 Saga (Collections): partly built. Case opens and closes, with retries. Overdue, blocked and write-off steps are not built.
- D-09 Upgrade saga with pivot ordering: not started. Conflict: Design Record §3 upgrades access at once, before payment, and Slice 1R gives access at sign-up. The course review recommended unlocking after the charge. Needs a decision, not a status.
- D-10 Saga countermeasures: not started. renew(n) plus the version check already stops a repeated renewal; no semantic locks.
- D-11 Event-carried state transfer vs notification: designed. ADR-016 (events carry what the listener needs); ADR-012 (compacted access facts)
- D-23 Reconciliation: partly built. Settlement reconciliation (proposed 021) is not built and is required before production.
- D2-01 Static vs dynamic coupling; coupling-point map: partly built. No explicit static vs dynamic coupling map for tax and payment.
- D2-03 Invoice state machine with tax: not started. Tax is not built.
- E-04 Holistic and continual fitness functions: partly built. Not yet 1,000,000 (needs staging). No continual check in production.
- E-05 Expand and contract: designed. Design Record lists a destructive-migration check as required before production.
- E-06 Migration linter (Squawk): designed. Design Record "Required before production": a migration check that rejects destructive changes
- F-02 Feature flags per account: partly built. The reason has a place for flags. No flag table or switch yet. ADR-010 rejects hosted flags with random percentages; the course review's OpenFeature suggestion must stay within that.
- F-03 Deployment pipeline: partly built. Staging and production stages from Design Record §14 do not exist.
- F-07 Cohort canary for billing logic: designed. ADR-010 per-account flags; ADR-009
- F-11 Observability with OpenTelemetry: partly built. No tracing; no trace context in event publications.
- F-13 SLOs and error budget: designed. No SLO or error budget defined as such.
- F-14 DORA four keys baseline: designed. Not measured yet.
- H-06 Probes and graceful shutdown: partly built. No separate liveness and readiness groups.
- H-09 Slice 2 messaging choice: designed. The course review's open choice is already decided.
- I-01 Threat model: not started. Design Record §15 risk register is not a threat model
- I-02 API authentication (subscriber app, back office): not started. Was "merchant API keys". The callers are the subscriber app and the back office.
- I-04 Object-level authorisation + cross-account test: not started. Was "cross-tenant". Subscriber A must not read subscriber B's invoice.
- I-05 One database user per module: not started. Was "row-level security + DB user per module". Row-level security for tenants does not apply.

Three of these the Design Record already lists as required before production: settlement reconciliation (D-23, proposed 021), authentication (I-02) and the destructive-migration check (E-06). D-23 was raised to high priority for that reason.

## 0 Decision drivers

| ID | Topic | Status | Was | Priority | Drivers | Evidence | Note |
|---|---|---|---|---|---|---|---|
| 0-01 | Quality attribute scenarios (six-part) | Built ◆ | Designed | High | D1–D15 | drivers.md; Design Record §8 | Fifteen drivers in six-part form, D1–D3 high value and high risk. The course review used its own S1–S7 instead; this tracker maps every row to the real D-numbers. |
| 0-02 | Top three characteristics | Built ◆ | Designed | High | All | drivers.md; Design Record §8 "Top three decision" | The top three are correctness, recoverability and deployability, with auditability and privacy as constraints. The course review recommended data integrity, modifiability and deployability; the Design Record's choice stands. |
| 0-03 | Utility tree | Built ◆ | Designed | High | All | Design Record §8 "Where each quality matters", value/risk ratings | Rated by value and risk per driver, and by context. |
| 0-04 | Tactics traced to scenarios | Built ◆ | Designed | Medium | D1–D13 | Design Record §13 "Scenario to mechanism" | Each driver traced to its mechanism and test. |
| 0-05 | Tradeoff and sensitivity points | Built ◆ | Designed | Medium | All | Design Record §15 walkthrough: sensitivity points and trade-offs T1–T5 | — |
| 0-06 | ADR-001 drivers and style scoring | Built ◆ | Not started | High | All | Design Record §9: ADR-001 with drivers, candidates A–E against drivers, alternatives rejected, compliance, revisit-when | The course review's request to "add a drivers section to ADR-001" is already met. |
| 0-07 | Architecture views (C4 levels 1–3) | Partly built ◆ | Not started | Medium | — | Design Record §13 lists containers in prose | No C4 diagrams in the repository. |
| 0-08 | Architecture evaluation | Built ◆ | Not started | Medium | All | Design Record §15 lightweight evaluation (SAIP ch. 21), risk register R1–R6, risk themes A–E | — |
| 0-09 | Coupling measures | Partly built ◆ | Not started | Low | — | Design Record §10 table: Ce, Ca and instability per module | Computed once by hand; no build metric tracks it. |

## A Domain

| ID | Topic | Status | Was | Priority | Drivers | Evidence | Note |
|---|---|---|---|---|---|---|---|
| A-01 | Bounded context and module boundary | Checked | Checked | High | — | PinnedModuleDependenciesTest; ModuleStructureTest; architecture/module-dependencies.txt | Nine modules per CLAUDE.md rule 1. |
| A-02 | Discovery method (Event Storming, message flow, canvas) | Built ◆ | Not started | High | — | Design Record §3 (pivotal events, three stories, domain story, boundary signals, hotspots), §4 (three designs scored on crossings), §7 canvases | — |
| A-03 | Subdomain types (core, supporting, generic) | Built ◆ | Designed | High | — | Design Record §5 Core Domain Chart | Core: Offers and Pricing. Supporting: Subscription, Access, Collections. Generic: Invoicing, Payment. The course review's open Collections debate is settled as supporting. |
| A-04 | Core Domain Chart and Wardley map | Built ◆ | Not started | Medium | — | Design Record §2 Wardley map; §5 Core Domain Chart | — |
| A-05 | Ubiquitous language glossary | Partly built ◆ | Not started | Medium | — | Design Record §6 settles account / subscriber / customer; §3 boundary signals | Terms are settled in the record; no glossary page. |
| A-06 | Effective-dated catalogue | Partly built ◆ | Not started | High | D4, D5, D7 | offers/domain/OfferVersion (number, kind, terms, publishedAt); Slice 1R rule: a subscriber keeps the version they signed up to | Versions are published and pinned per subscriber. No future effective date: a version applies from publication. |
| A-07 | Full context map | Built ◆ | Not started | Low | — | Design Record §6 relationships table; design.md §3 | — |
| A-08 | Shared kernel | Checked | Checked | Medium | — | SharedKernelTest (F4); drivers.md R5 | R5 deliberately puts the event marker in the kernel. The review's DomainEvent concern is a decision, not drift. |
| A-09 | Open host service and published language | Built | Built | Medium | D5 | offers.Pricing, offers.PriceQuote; R4 | — |
| A-10 | Customer–supplier and conformist | Built | Built | Medium | — | Design Record §6; Account is CF to all contexts | — |
| A-11 | Anti-corruption layer | Partly built | Partly built | Medium | D11 | payment/adapter/provider/HttpPaymentGateway | Provider translation built. Tax service and telecom partner translators are not built. |
| A-12 | Aggregate and invariant | Checked ◆ | Built | High | D1, D2 | Subscription.renew(n), Invoice, CollectionRequest; aggregate and exhaustive transition tests (ADR-018) | — |
| A-13 | Domain event | Built | Built | Medium | D3 | shared.DomainEvent; invoicing.InvoiceIssued and others | — |
| A-14 | One-active-subscription rule | Checked ◆ | Designed | High | D1 | V5__subscription.sql: CONSTRAINT subscription_one_per_account UNIQUE (account_id) | Stricter than the course review assumed: one subscription per account, enforced by the database. |
| A-15 | Aggregate root encapsulation check | Not started | Not started | Medium | — | — | No test stops code outside an aggregate changing its parts. |

## B Architecture styles

| ID | Topic | Status | Was | Priority | Drivers | Evidence | Note |
|---|---|---|---|---|---|---|---|
| B-01 | Architecture quantum | Partly built ◆ | Designed | High | D13 | access module with its own tables; F12AccessReactsOnlyToEventsTest; AccessIT | Access is a separate quantum on paper (ADR-001) and in-process today. Extraction is Slice 2. |
| B-02 | Modular monolith | Checked ◆ | Built | High | D1, D3, D10 | ADR-001; pinned module graph tests | — |
| B-03 | Event-driven architecture (in-process) | Built | Built | Medium | D3 | Spring Modulith events with the publication registry (ADR-002) | — |
| B-04 | Choreography versus orchestration | Built | Built | Medium | — | ADR-005: renewal choreographed; Collections orchestrated | — |
| B-05 | Style scoring against drivers | Built ◆ | Designed | High | All | Design Record §9 "Candidates against the drivers" | — |
| B-06 | Microkernel for providers | Built ◆ | Designed | Medium | D11 | payment/domain/PaymentGateway port; HttpPaymentGateway adapter | A port with one adapter, not runtime plug-ins. That meets D11. |
| B-07 | Pipeline for usage rating | Not applicable ◆ | Not started | Low | — | Design Record §1: monthly plans, promotions, trials | Tally has no usage billing. |
| B-08 | Access with its own database | Designed | Designed | High | D13 | ADR-001; ADR-007 (Access has its own small PostgreSQL) | — |

## C Inside a module

| ID | Topic | Status | Was | Priority | Drivers | Evidence | Note |
|---|---|---|---|---|---|---|---|
| C-01 | Ports and adapters (hexagonal) | Checked | Checked | High | D2, D10, D11 | F8InwardDependenciesTest; F9PlainDomainTest | — |
| C-02 | Closed layered architecture | Checked | Checked | Medium | — | F10ClosedLayersTest; F11ClosedLayerDomainTest | — |
| C-03 | The dependency rule | Checked ◆ | Built | High | — | F8InwardDependenciesTest | — |
| C-04 | Functional core for pricing and proration | Partly built ◆ | Designed | Medium | D5 | ADR-015: one PricingRule per kind, registry, start-up check (PricingRulesCheck); PricingRuleRegistryTest | Rules are separate per kind; not designed as a pure functional core. |
| C-05 | CQRS-lite for list endpoints | Not started | Not started | Low | — | — | — |
| C-06 | Collections state machine in the domain | Partly built ◆ | Not started | Medium | — | collections/domain/CollectionCase with status and version | The case is a domain object but has only OPEN and CLOSED. The day 3/7/10/30 machine of Design Record §16 is not built. |

## D Data and reliability

| ID | Topic | Status | Was | Priority | Drivers | Evidence | Note |
|---|---|---|---|---|---|---|---|
| D-01 | Database per module | Built | Built | High | — | One schema per module (V1__module_schemas.sql); CLAUDE.md rule 2 | F5 (schemas) and F6 (cross-schema foreign keys) from Design Record §10 have no test in the code. |
| D-02 | Transactional outbox | Checked ◆ | Built | High | D3 | ADR-002; AT-7, AT-8, AT-17 | — |
| D-03 | Idempotent consumer | Checked ◆ | Built | High | D3 | Business-key guards (rule 11); AT-7, AT-8; PinnedListenersTest | — |
| D-04 | Idempotency key | Checked ◆ | Built | High | D2 | ADR-027; platform IdempotentEndpoints; AT-13; SignUpRecoveryIT | — |
| D-05 | Competing consumers, leaderless | Checked ◆ | Built | High | D1, D6 | ADR-003; JdbcDurableQueue and PeriodicJob use FOR UPDATE SKIP LOCKED; AT-9; DurableQueueIT | — |
| D-06 | Retry, back-off and circuit breaker | Checked ◆ | Built | High | D2 | ADR-013; payment/application/ProviderIsolation (Resilience4j breaker and rate limiter); AT-4; ProviderIsolationIT | No jitter found in retries. |
| D-07 | Saga (Collections) | Partly built | Partly built | High | — | collections module; V24__collections.sql (case retries); V23 collection request retries | Case opens and closes, with retries. Overdue, blocked and write-off steps are not built. |
| D-08 | CQRS, read model and materialised view | Partly built ◆ | Designed | Medium | D13 | access module answers from its own tables (F12) | In-process copy today. The compacted log of ADR-012 is Slice 2. |
| D-09 | Upgrade saga with pivot ordering | Not started ◆ | Designed | High | — | No plan change in code (no changePlan or PlanChanged) | Conflict: Design Record §3 upgrades access at once, before payment, and Slice 1R gives access at sign-up. The course review recommended unlocking after the charge. Needs a decision, not a status. |
| D-10 | Saga countermeasures | Not started | Not started | High | — | — | renew(n) plus the version check already stops a repeated renewal; no semantic locks. |
| D-11 | Event-carried state transfer vs notification | Designed | Designed | High | D13 | ADR-016 (events carry what the listener needs); ADR-012 (compacted access facts) | — |
| D-12 | Optimistic locking on aggregates | Built ◆ | Not started | High | D1 | @Version on Subscription, Invoice, CollectionRequest, CollectionCase | — |
| D-13 | Database constraints for invariants | Built ◆ | Not started | High | D1, D2, D3 | Named unique constraints, e.g. subscription_period_one_per_number, collection_request_one_per_reference, payment_attempt_one_per_key; CLAUDE.md rule 11 | — |
| D-14 | Partitioned renewal run (single writer) | Not started | Not started | Medium | D6 | ADR-003 claims batches; no partitioning | Not needed at D6's 14 a second. |
| D-15 | Clock as a dependency | Checked ◆ | Not started | High | D10 | platform/ClockConfiguration; ArchitectureRulesTest bans no-argument now() and currentTimeMillis | — |
| D-16 | Multi-tenancy model | Not applicable ◆ | Not started | High | — | Design Record §1: one streaming business billing households | Tally has no merchants or tenants. |
| D-17 | Event ordering per key | Built ◆ | Not started | Medium | D13 | shared.DomainEvent carries the sequence (ADR-016); FF-13 treats a missing sequence as "order not known" | — |
| D-18 | Read-your-own-writes | Designed | Designed | Medium | D13 | ADR-014: 99% within 5 seconds; app shows "activating" | — |
| D-19 | Bulkhead for provider calls | Built ◆ | Not started | Medium | D2 | ProviderIsolation: "The worker pool is the bulkhead" | — |
| D-20 | Dead-letter queue with alert | Partly built ◆ | Not started | Medium | D3, D9 | tally.queue.failed and tally.events.unreadable metrics; ADR-016 isolates each publication | Failures are counted and visible. No separate dead-letter store or alert rule. |
| D-21 | API composition | Designed ◆ | Not started | Low | — | Design Record §6: support view as a read model over published events | Not built. |
| D-22 | Catalogue cache by version | Not started | Not started | Low | — | — | — |
| D-23 | Reconciliation | Partly built ◆ | Not started | High | D2, D14 | payment/application/Reconciliation (unknown resolution, ADR-026); InvariantJobs with InvoicedVersusCollected (AT-12) | Settlement reconciliation (proposed 021) is not built and is required before production. |
| D-24 | RPO, RTO and tested restore | Designed ◆ | Not started | Medium | D8 | ADR-020: 7-day point-in-time recovery, 25-day snapshots, deletion log | No restore drill yet. |

## D2 Distributed coordination

| ID | Topic | Status | Was | Priority | Drivers | Evidence | Note |
|---|---|---|---|---|---|---|---|
| D2-01 | Static vs dynamic coupling; coupling-point map | Partly built ◆ | Not started | High | — | Design Record §6 message flows; ADR-001 (sync inside billing, async to Access) | No explicit static vs dynamic coupling map for tax and payment. |
| D2-02 | Eight-saga matrix applied to workflows | Designed | Designed | Medium | — | ADR-005: renew and invoice compensatable, charge the pivot, recording retriable | — |
| D2-03 | Invoice state machine with tax | Not started | Not started | High | D12 | No tax code; TaxRates port designed (Design Record §10) | Tax is not built. |
| D2-04 | Tax quote fallback decision | Not started ◆ | Designed | Medium | D12 | — | No decision on behaviour when the tax service is down. |
| D2-05 | State management over compensation | Built ◆ | Designed | High | D2 | AttemptState UNKNOWN; ADR-026; tally.payment.unknown_past_key_window alert; declines start Collections | — |
| D2-06 | Eventual consistency patterns | Built ◆ | Designed | Medium | D3 | Event-based: InvoiceIssued and PaymentSucceeded listeners | — |
| D2-07 | Joint ownership: delegate | Built | Built | Low | — | Payment publishes outcomes by reference; Invoicing records them (R1) | — |
| D2-08 | Distributed data access patterns | Designed | Designed | Medium | D13 | ADR-012 Access copy | — |
| D2-09 | Contracts and stamp coupling | Designed ◆ | Not started | Medium | — | ADR-016: events carry what the listener needs | No review of event size against consumers yet. |
| D2-10 | Saga participation marked in code | Not started | Not started | Low | — | — | — |
| D2-11 | Granularity of the tax adapter | Designed | Designed | Low | D12 | FR-016 kept: tax bought behind the TaxRates port in Invoicing | — |

## E Evolution and governance

| ID | Topic | Status | Was | Priority | Drivers | Evidence | Note |
|---|---|---|---|---|---|---|---|
| E-01 | Architecture decision records | Built | Built | High | — | docs/architecture/v2/README.md; accepted text never edited | — |
| E-02 | ADR template v2 | Built ◆ | Not started | High | — | ADR-001 has status, context, decision, consequences, compliance, alternatives rejected and revisit-when; the log has Drivers and Checked-by columns | Only an "advice sought" line is missing. |
| E-03 | Fitness functions | Checked ◆ | Built | High | — | tally/src/test/java/com/tally/architecture (F3, F8–F13, FF-12, FF-13, pinned events and listeners) | — |
| E-04 | Holistic and continual fitness functions | Partly built ◆ | Not started | High | D1, D6 | .github/workflows/nightly.yml runs AT-9 (5,000 accounts) and AT-10 (10,000 renewals) | Not yet 1,000,000 (needs staging). No continual check in production. |
| E-05 | Expand and contract | Designed | Designed | High | D5 | ADR-009 | Design Record lists a destructive-migration check as required before production. |
| E-06 | Migration linter (Squawk) | Designed ◆ | Not started | High | D5 | Design Record "Required before production": a migration check that rejects destructive changes | — |
| E-07 | Tolerant reader and event versioning | Checked | Checked | High | D3, D13 | ADR-016, ADR-024, ADR-028; EventFieldsMayBeMissingTest (FF-13); EnumsHaveUnknownDefaultTest (FF-12) | — |
| E-08 | Upcasting old events | Not applicable ◆ | Not started | Medium | — | CLAUDE.md rule 7 and FF-13: added fields are nullable, readers treat missing as not known | The add-only rule removes the need for upcasting so far. |
| E-09 | Contract-first API | Checked | Checked | Medium | — | docs/api/openapi.yaml; ContractIT (ADR-019) | — |
| E-10 | Breaking-change check (oasdiff) | Not started | Not started | Medium | — | — | — |
| E-11 | Consumer-driven contracts | Designed | Designed | Medium | D13 | ADR-011 | Slice 2. |
| E-12 | Public API versioning strategy | Built ◆ | Designed | Medium | — | ADR-019: version in the path; openapi.yaml paths start /v1 | — |
| E-13 | Parallel run for proration replacement | Not started | Not started | Medium | — | — | — |
| E-14 | Dependency freshness (temporal check) | Not started | Not started | Low | — | FR-039 dependency version policy | Policy exists; no automated upgrade or freshness check. |
| E-15 | Governance model | Built ◆ | Designed | Low | — | CLAUDE.md "Stop and ask"; decisions posted verbatim on PRs; fitness functions in the build | — |

## F Delivery

| ID | Topic | Status | Was | Priority | Drivers | Evidence | Note |
|---|---|---|---|---|---|---|---|
| F-01 | Trunk-based development | Built | Built | High | D5 | ADR-008; main is the trunk | — |
| F-02 | Feature flags per account | Partly built ◆ | Designed | High | D5, D7 | ADR-010; V19__quote_reason_jsonb.sql: the PriceQuote reason stored on each invoice carries a flags map | The reason has a place for flags. No flag table or switch yet. ADR-010 rejects hosted flags with random percentages; the course review's OpenFeature suggestion must stay within that. |
| F-03 | Deployment pipeline | Partly built ◆ | Not started | High | D5 | .github/workflows/ci.yml: commit stage and acceptance stage; nightly.yml | Staging and production stages from Design Record §14 do not exist. |
| F-04 | Build once, promote by digest | Built ◆ | Not started | High | D5 | ci.yml: image per commit tagged by SHA; accepted-<sha> added by manifest, nothing rebuilt | — |
| F-05 | Test strategy | Built ◆ | Partly built | Medium | D10 | Unit, module, acceptance AT-1–AT-17, ContractIT, fitness tests; ADR-029 database per test class | — |
| F-06 | Security scans in commit stage | Not started | Not started | Medium | — | ci.yml has no scan steps | — |
| F-07 | Cohort canary for billing logic | Designed | Designed | High | D5 | ADR-010 per-account flags; ADR-009 | — |
| F-08 | Rolling deploys | Designed ◆ | Not started | Medium | D5 | ADR-009 rolling deploys with automatic rollback | No deployment environment yet. |
| F-09 | Ops flag: pause provider calls | Partly built ◆ | Not started | Medium | D2 | ProviderIsolation is the payment queue's ClaimGate: an open breaker stops claiming | Automatic only; no manual switch. |
| F-10 | Infrastructure as code | Not started | Not started | Medium | — | infra/docker-compose.yml is for local runs; FR-006 kept | — |
| F-11 | Observability with OpenTelemetry | Partly built ◆ | Not started | High | D9 | Micrometer metrics on /actuator/prometheus; PrometheusEndpointIT | No tracing; no trace context in event publications. |
| F-12 | Business metrics | Partly built ◆ | Not started | Medium | D2, D3, D9 | tally.payment.*, tally.queue.*, tally.events.*, tally.invariant.violations | Health and money-path metrics exist. Invoices per minute and renewal progress do not. |
| F-13 | SLOs and error budget | Designed ◆ | Not started | High | D9, D13 | Driver measures (D9 5 minutes, D13 99.9%) | No SLO or error budget defined as such. |
| F-14 | DORA four keys baseline | Designed ◆ | Not started | High | D5 | Design Record §14 delivery targets | Not measured yet. |
| F-15 | Value stream map | Not started | Not started | Low | — | — | — |
| F-16 | Post-incident review and game day | Not started | Not started | Low | — | — | — |
| F-17 | Golden path | Designed ◆ | Not started | Medium | G9 | Design Record §14: "a one-page golden path"; no platform team | — |

## G Teams

| ID | Topic | Status | Was | Priority | Drivers | Evidence | Note |
|---|---|---|---|---|---|---|---|
| G-01 | Conway's law and inverse Conway manoeuvre | Built ◆ | Designed | Medium | — | Design Record §11 | — |
| G-02 | Team design | Designed | Designed | Medium | G9 | Design Record §11: one stream-aligned team of about six now; planned split S1 into Subscriber and Revenue teams, with triggers | Differs from the course review's options A and B. The Design Record's split stands. |
| G-03 | Upgrade saga has one owning team | Not applicable ◆ | Designed | Medium | — | Design Record §11: one team owns everything | Revisit at the split. |
| G-04 | Interaction modes over time | Designed | Designed | Low | — | Design Record §11 "Interactions now" | — |
| G-05 | Ownership in the repo | Not started | Not started | Medium | — | No CODEOWNERS file | Low value with one team. |
| G-06 | Shared kernel needs all owners' approval | Not started | Not started | Low | — | — | Low value with one team. |
| G-07 | Cross-team change measurement | Not started | Not started | Low | — | — | — |
| G-08 | Complicated-subsystem trigger | Designed | Designed | Low | — | Design Record §11 cognitive-load table; no complicated-subsystem team | — |

## H Runtime infrastructure

| ID | Topic | Status | Was | Priority | Drivers | Evidence | Note |
|---|---|---|---|---|---|---|---|
| H-01 | Hosting choice | Designed | Designed | Medium | D1–D3, G9 | ADR-007; Design Record §14 (managed containers, managed database and event log) | — |
| H-02 | Two process types from one image | Not started ◆ | Designed | Medium | D6 | Design Record §13: one billing application runs all roles | New idea from the course review; not in the Design Record. |
| H-03 | Renewal run: enqueue, then workers | Checked ◆ | Partly built | High | D1, D6, D9 | PeriodicJob and per-module queue tables with business unique constraints (CLAUDE.md rule 5); AT-9; AT-10 | — |
| H-04 | Capacity sizing (Little's law) and pre-scaling | Partly built ◆ | Not started | Medium | D6 | D6: about 14 a second; AT-10 throughput test | No pre-scaling; no deployment. |
| H-05 | Connection sizing and PgBouncer | Not started | Not started | Medium | D6 | — | — |
| H-06 | Probes and graceful shutdown | Partly built ◆ | Not started | High | D9 | QueueRuntime stops pollers on shutdown (fix from the Slice 2 spike); actuator health exposed | No separate liveness and readiness groups. |
| H-07 | Front door and rate limits | Not started | Not started | Medium | — | Outbound provider rate limit is built (ProviderIsolation); nothing inbound | — |
| H-08 | One layer owns retries | Designed | Designed | Low | — | Course review only; the Design Record has no service mesh | Retries live in Payment (ADR-013). |
| H-09 | Slice 2 messaging choice | Designed | Designed | High | D13 | ADR-006 and ADR-012: a managed Kafka-compatible compacted log | The course review's open choice is already decided. |
| H-10 | Multi-AZ deployment | Designed ◆ | Not started | Medium | D1–D3 | ADR-007: synchronous standby; Design Record §13: 2+ copies | — |
| H-11 | Multi-region triggers | Not started | Not started | Low | — | Constraint C4: personal data stays in the region | — |

## I Security

| ID | Topic | Status | Was | Priority | Drivers | Evidence | Note |
|---|---|---|---|---|---|---|---|
| I-01 | Threat model | Not started | Not started | High | C2, D8 | Design Record §15 risk register is not a threat model | — |
| I-02 | API authentication (subscriber app, back office) | Not started | Not started | High | D2 | Design Record "Required before production": authentication, with keys scoped per caller | Was "merchant API keys". The callers are the subscriber app and the back office. |
| I-03 | Human login via OIDC | Not started | Not started | Medium | — | — | — |
| I-04 | Object-level authorisation + cross-account test | Not started | Not started | High | — | ADR-027 scopes idempotency keys per account until callers are authenticated | Was "cross-tenant". Subscriber A must not read subscriber B's invoice. |
| I-05 | One database user per module | Not started | Not started | High | — | — | Was "row-level security + DB user per module". Row-level security for tenants does not apply. |
| I-06 | RBAC and four-eyes refunds | Not started | Not started | Medium | — | — | — |
| I-07 | PCI scope: hosted fields | Built ◆ | Designed | High | C2 | FR-015; fake-provider ProviderController simulates the hosted card form | No log-scanning test for card numbers. |
| I-08 | Inbound webhook verification | Not applicable ◆ | Not started | High | D2 | ADR-026: Tally asks the provider by reference; no webhooks are consumed | Becomes relevant if a provider pushes outcomes. |
| I-09 | Outbound webhooks | Not applicable ◆ | Not started | Medium | — | No merchant callbacks in Tally | — |
| I-10 | Secrets and keys | Not started | Not started | Medium | — | application.yml holds local credentials only | — |
| I-11 | No personal data in events | Designed ◆ | Not started | Medium | D8 | ADR-006: IDs only, no personal data; "Event payload scan" planned | Slice 2. |
| I-12 | Audit log | Partly built ◆ | Not started | Medium | D7 | Each invoice stores the PriceQuote reason as data (QuoteReason, V19) | Traces charges (D7). No audit of staff actions. |
| I-13 | Supply chain | Not started | Not started | Low | — | — | — |

◆ marks a status changed by this check.

## Keeping it current

Change a row in the pull request that changes its status, and name the evidence. A row reaches Checked only when a test that fails the build exists; never for a check that is only planned (CLAUDE.md, slice report item 6). Keep the CSV and this file in step.
