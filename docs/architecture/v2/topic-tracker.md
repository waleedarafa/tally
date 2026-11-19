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

"Was" is the status the course table gave before this check. "New" marks a row the repository check added.

## Summary

| Level | Topics | Not started | Designed | Partly built | Built | Checked | Not applicable |
|---|---|---|---|---|---|---|---|
| 0 Decision drivers | 9 |  |  | 2 | 7 |  |  |
| A Domain | 15 | 1 |  | 2 | 8 | 4 |  |
| B Architecture styles | 8 |  |  |  | 4 | 3 | 1 |
| C Inside a module | 6 | 1 |  | 1 |  | 4 |  |
| D Data and reliability | 27 | 3 | 3 | 3 | 4 | 13 | 1 |
| D2 Distributed coordination | 11 | 3 | 2 | 1 | 4 | 1 |  |
| E Evolution and governance | 15 | 3 |  | 2 | 4 | 5 | 1 |
| F Delivery | 17 | 4 | 5 | 5 | 3 |  |  |
| G Teams | 8 | 3 | 3 |  | 1 |  | 1 |
| H Runtime infrastructure | 11 | 4 | 3 | 2 | 1 | 1 |  |
| I Security | 13 | 6 | 2 | 1 | 1 | 1 | 2 |
| **All** | **140** | **28** | **18** | **19** | **37** | **32** | **6** |

Before this check the course table had 24 topics built or checked. After it, 54. 78 of 138 statuses changed.

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

**5. The renewal chain has no check.** Each renewal schedules the next one (H-03), so a missing row is silent: the D9 alarm sees late work, not work never scheduled. D-25 adds the invariant check this needs; it was found while reviewing durable queues, and is now built and tested.

**6. The largest open gaps.** These are high priority and not yet built or checked:

- A-06 Effective-dated catalogue: partly built. Versions are published and pinned per subscriber. No future effective date: a version applies from publication.
- B-01 Architecture quantum: partly built. Access is a separate quantum on paper (ADR-001) and in-process today. Extraction is Slice 2.
- B-08 Access with its own database: designed. ADR-001; ADR-007 (Access has its own small PostgreSQL)
- D-07 Saga (Collections): partly built. Case opens and closes, with retries. Overdue, blocked and write-off steps are not built.
- D-09 Upgrade saga with pivot ordering: not started. Conflict: Design Record §3 upgrades access at once, before payment, and Slice 1R gives access at sign-up. The course review recommended unlocking after the charge. Needs a decision, not a status.
- D-10 Saga countermeasures: not started. renew(n) plus the version check already stops a repeated renewal; no semantic locks.
- D-11 Event-carried state transfer vs notification: designed. ADR-016 (events carry what the listener needs); ADR-012 (compacted access facts)
- D-27 Command for a person's decision on an unknown payment past the key window: partly built. Alert and runbook exist; the runbook says to change the database by hand.
- D2-01 Static vs dynamic coupling; coupling-point map: partly built. No explicit static vs dynamic coupling map for tax and payment.
- D2-03 Invoice state machine with tax: not started. Tax is not built.
- E-04 Holistic and continual fitness functions: partly built. Not yet 1,000,000 (needs staging). No continual check in production.
- E-05 Expand and contract: partly built. The migration check (E-06) is built; no change has yet been made in two releases, and the N-1 replay does not exist.
- F-02 Feature flags per account: partly built. The reason has a place for flags. No flag table or switch yet. ADR-010 rejects hosted flags with random percentages; the course review's OpenFeature suggestion must stay within that.
- F-03 Deployment pipeline: partly built. Staging and production stages from Design Record §14 do not exist.
- F-07 Cohort canary for billing logic: designed. ADR-010 per-account flags; ADR-009
- F-11 Observability with OpenTelemetry: partly built. No tracing; no trace context in event publications.
- F-13 SLOs and error budget: designed. No SLO or error budget defined as such.
- F-14 DORA four keys baseline: designed. Not measured yet.
- H-06 Probes and graceful shutdown: partly built. No separate liveness and readiness groups.
- H-09 Slice 2 messaging choice: designed. The course review's open choice is already decided.
- I-01 Threat model: not started. Design Record §15 risk register is not a threat model
- I-02 API authentication (subscriber app, back office): checked (Slice 4) for the subscriber app and the streaming platform; staff roles come with the first back-office endpoint. Was "merchant API keys".
- I-04 Object-level authorisation + cross-account test: checked (Slice 4): AT-27 and F16. Was "cross-tenant". Subscriber A must not read subscriber B's invoice.
- I-05 One database user per module: not started. Was "row-level security + DB user per module". Row-level security for tenants does not apply.

The Design Record lists four items as required before production: settlement reconciliation (D-23, proposed 021, now checked), authentication (I-02), a command to record a person's decision on an unknown payment past the key window (D-27), and the destructive-migration check (E-06, now checked). D-23 was raised to high priority for that reason. The first version of this tracker named only three; D-27 was missing.

**7. One subscription per account is a scope limit, not a rule.** `subscription_one_per_account` (A-14) comes from the Slice 1 brief. The Design Record assumes the opposite: one account can pay for several subscriptions. The renewal queue key is per account (D-26), so lifting the limit as it stands would drop a second subscription's renewal. Fix D-26 first.

## 0 Decision drivers

| ID | Topic | Status | Was | Priority | Drivers | Evidence | Note |
|---|---|---|---|---|---|---|---|
| 0-01 | Quality attribute scenarios (six-part) | Built ◆ | Designed | High | D1–D15 | drivers.md; Design Record §8 | Fifteen drivers in six-part form, D1–D3 high value and high risk. The course review used its own S1–S7 instead; this tracker maps every row to the real D-numbers. |
| 0-02 | Top three characteristics | Built ◆ | Designed | High | All | drivers.md; Design Record §8 "Top three decision" | The top three are correctness, recoverability and deployability, with auditability and privacy as constraints. The course review recommended data integrity, modifiability and deployability; the Design Record's choice stands. |
| 0-03 | Utility tree | Built ◆ | Designed | High | All | Design Record §8 "Where each quality matters", value/risk ratings | Rated by value and risk per driver, and by context. |
| 0-04 | Tactics traced to scenarios | Built ◆ | Designed | Medium | D1–D13 | Design Record §13 "Scenario to mechanism" | Each driver traced to its mechanism and test. |
| 0-05 | Tradeoff and sensitivity points | Built ◆ | Designed | Medium | All | Design Record §15 walkthrough: sensitivity points and trade-offs T1–T5 | — |
| 0-06 | ADR-001 drivers and style scoring | Built ◆ | Not started | High | All | Design Record §9: ADR-001 with drivers, candidates A–E against drivers, alternatives rejected, compliance, revisit-when | The course review's request to "add a drivers section to ADR-001" is already met. |
| 0-07 | Architecture views (C4 levels 1–3) | Partly built ◆ | Not started | Medium | — | docs/architecture/v2/components.puml (level 3), generated by Spring Modulith's Documenter and checked by ComponentDiagramTest; Design Record §13 lists containers in prose | Level 3 is generated from the code and fails the build when the committed copy drifts. Levels 1 and 2 are not drawn. |
| 0-08 | Architecture evaluation | Built ◆ | Not started | Medium | All | Design Record §15 lightweight evaluation (SAIP ch. 21), risk register R1–R6, risk themes A–E | — |
| 0-09 | Coupling measures | Partly built ◆ | Not started | Low | — | Design Record §10 table: Ce, Ca and instability per module | Computed once by hand; no build metric tracks it. |

## A Domain

| ID | Topic | Status | Was | Priority | Drivers | Evidence | Note |
|---|---|---|---|---|---|---|---|
| A-01 | Bounded context and module boundary | Checked | Checked | High | — | PinnedModuleDependenciesTest; ModuleStructureTest; architecture/module-dependencies.txt | Nine modules per CLAUDE.md rule 1. |
| A-02 | Discovery method (Event Storming, message flow, canvas) | Built ◆ | Not started | High | — | Design Record §3 (pivotal events, three stories, domain story, boundary signals, hotspots), §4 (three designs scored on crossings), §7 canvases | — |
| A-03 | Subdomain types (core, supporting, generic) | Built ◆ | Designed | High | — | Design Record §5 Core Domain Chart | Core: Offers and Pricing. Supporting: Subscription, Access, Collections. Generic: Invoicing, Payment. The course review's open Collections debate is settled as supporting. |
| A-04 | Core Domain Chart and Wardley map | Built ◆ | Not started | Medium | — | Design Record §2 Wardley map; §5 Core Domain Chart | — |
| A-05 | Ubiquitous language glossary | Built ◆ | Not started | Medium | — | docs/architecture/v2/glossary.md: one section per module, each term sourced to the Design Record or a decision record | No check that names in code match the glossary. A new term enters the glossary in the pull request that introduces it. |
| A-06 | Effective-dated catalogue | Partly built ◆ | Not started | High | D4, D5, D7 | offers/domain/OfferVersion (number, kind, terms, publishedAt); Slice 1R rule: a subscriber keeps the version they signed up to | Versions are published and pinned per subscriber. No future effective date: a version applies from publication. |
| A-07 | Full context map | Built ◆ | Not started | Low | — | Design Record §6 relationships table; design.md §3 context map picture | Drawn as one picture in design.md §3, upstream on top, with the pattern on each relationship. |
| A-08 | Shared kernel | Checked | Checked | Medium | — | SharedKernelTest (F4); drivers.md R5 | R5 deliberately puts the event marker in the kernel. The review's DomainEvent concern is a decision, not drift. |
| A-09 | Open host service and published language | Built | Built | Medium | D5 | offers.Pricing, offers.PriceQuote; R4 | — |
| A-10 | Customer–supplier and conformist | Built | Built | Medium | — | Design Record §6; Account is CF to all contexts | — |
| A-11 | Anti-corruption layer | Partly built | Partly built | Medium | D11 | payment/adapter/provider/HttpPaymentGateway | Provider translation built. Tax service and telecom partner translators are not built. |
| A-12 | Aggregate and invariant | Checked ◆ | Built | High | D1, D2 | Subscription.renew(n), Invoice, CollectionRequest; aggregate and exhaustive transition tests (ADR-018) | — |
| A-13 | Domain event | Built | Built | Medium | D3 | shared.DomainEvent; invoicing.InvoiceIssued and others | — |
| A-14 | One-active-subscription rule | Checked ◆ | Designed | High | D1 | V37__subscription_end.sql: UNIQUE INDEX subscription_one_live_per_account ON (account_id) WHERE status = 'ACTIVE'; SubscriptionApiIT | One live subscription per account, enforced by the database (Slice 3 PR-2); an account whose subscription ended may sign up again. Before, one subscription per account (V5, a Slice 1 scope limit). It is still a scope limit, not a decision: the Design Record assumes one account can pay for several subscriptions (Step 6, Step 7). D-26 is fixed. |
| A-15 | Aggregate root encapsulation check | Not started | Not started | Medium | — | — | No test stops code outside an aggregate changing its parts. |

## B Architecture styles

| ID | Topic | Status | Was | Priority | Drivers | Evidence | Note |
|---|---|---|---|---|---|---|---|
| B-01 | Architecture quantum | Checked ◆ | Designed | High | D13 | access/ (com.tallyaccess): its own process, database, image and pipeline (access.yml); NoBillingCodeTest (F7, Access side); AT18AccessSurvivesBillingIT | Extracted in Slice 2: billing and Access share only contracts/access-facts/. The in-process module left in PR-9 (#72). |
| B-02 | Modular monolith | Checked ◆ | Built | High | D1, D3, D10 | ADR-001; pinned module graph tests | — |
| B-03 | Event-driven architecture (in-process) | Built | Built | Medium | D3 | Spring Modulith events with the publication registry (ADR-002) | — |
| B-04 | Choreography versus orchestration | Built | Built | Medium | — | ADR-005: renewal choreographed; Collections orchestrated | — |
| B-05 | Style scoring against drivers | Built ◆ | Designed | High | All | Design Record §9 "Candidates against the drivers" | — |
| B-06 | Microkernel for providers | Built ◆ | Designed | Medium | D11 | payment/domain/PaymentGateway port; HttpPaymentGateway adapter | A port with one adapter, not runtime plug-ins. That meets D11. |
| B-07 | Pipeline for usage rating | Not applicable ◆ | Not started | Low | — | Design Record §1: monthly plans, promotions, trials | Tally has no usage billing. |
| B-08 | Access with its own database | Checked | Designed | High | D13 | access/: Flyway schema access (V1, V2) in its own PostgreSQL (infra/docker-compose.yml access-postgres; a container of its own in tests); AT-18 stops billing's database and Access still answers | Which managed PostgreSQL is chosen with the first deployed environment (ADR-007). |

## C Inside a module

| ID | Topic | Status | Was | Priority | Drivers | Evidence | Note |
|---|---|---|---|---|---|---|---|
| C-01 | Ports and adapters (hexagonal) | Checked | Checked | High | D2, D10, D11 | F8InwardDependenciesTest; F9PlainDomainTest | — |
| C-02 | Closed layered architecture | Checked | Checked | Medium | — | F10ClosedLayersTest; F11ClosedLayerDomainTest | — |
| C-03 | The dependency rule | Checked ◆ | Built | High | — | F8InwardDependenciesTest | — |
| C-04 | Functional core for pricing and proration | Partly built ◆ | Designed | Medium | D5 | ADR-015: one PricingRule per kind, registry, start-up check (PricingRulesCheck); PricingRuleRegistryTest | Rules are separate per kind; not designed as a pure functional core. |
| C-05 | CQRS-lite for list endpoints | Not started | Not started | Low | — | — | — |
| C-06 | Collections state machine in the domain | Checked ◆ | Not started | Medium | — | collections/domain/CollectionCase (OPEN, OVERDUE, BLOCKED, WRITTEN_OFF, CLOSED; `take(step)` by rank) and CaseStep; CaseTimetableIT; AT-22 | Built in Slice 3 PR-4. A step moves a case with a debt forward to its state, never back, from whatever state it finds; the standing follows in the same transaction. Gap: no unit test of `take` by itself (Slice 3 report §6). |

## D Data and reliability

| ID | Topic | Status | Was | Priority | Drivers | Evidence | Note |
|---|---|---|---|---|---|---|---|
| D-01 | Database per module | Built | Built | High | — | One schema per module (V1__module_schemas.sql); CLAUDE.md rule 2 | F5 (schemas) and F6 (cross-schema foreign keys) from Design Record §10 have no test in the code. |
| D-02 | Transactional outbox | Checked ◆ | Built | High | D3 | ADR-002; AT-7, AT-8, AT-17 | — |
| D-03 | Idempotent consumer | Checked ◆ | Built | High | D3 | Business-key guards (rule 11); AT-7, AT-8; PinnedListenersTest | — |
| D-04 | Idempotency key | Checked ◆ | Built | High | D2 | ADR-027; platform IdempotentEndpoints; AT-13; SignUpRecoveryIT | — |
| D-05 | Competing consumers, leaderless | Checked ◆ | Built | High | D1, D6 | ADR-003; JdbcDurableQueue and PeriodicJob use FOR UPDATE SKIP LOCKED; AT-9; DurableQueueIT | — |
| D-06 | Retry, back-off and circuit breaker | Checked ◆ | Built | High | D2 | ADR-013; payment/application/ProviderIsolation (Resilience4j breaker and rate limiter); AT-4; ProviderIsolationIT | No jitter found in retries. |
| D-07 | Saga (Collections) | Checked ◆ | Partly built | High | — | collections module; V39 (case_invoice), V40 (timer queue, case_step), V41 (pay now keys), V42 (retry steps); AT-22, AT-23, AT-25; CaseTimetableIT; PayNowIT | The whole timetable (Slice 3): retries on day 3 and day 7, OVERDUE, BLOCKED, WRITTEN_OFF, "pay now", one case for every unpaid invoice. Each timer is a claimed row with a business guard; the write-off ends the subscription. |
| D-08 | CQRS, read model and materialised view | Checked ◆ | Designed | Medium | D13 | Access's copy (access.facts) is built from the compacted topic access-facts (ADR-012, ADR-033, ADR-035); AccessLogIT; AT19RebuildIT (with and without compaction, old consumer group) | A rebuild has downtime: every instance refuses answers until its copy has caught up (slice-2-report §4). |
| D-09 | Upgrade saga with pivot ordering | Not started ◆ | Designed | High | — | No plan change in code (no changePlan or PlanChanged) | Conflict: Design Record §3 upgrades access at once, before payment, and Slice 1R gives access at sign-up. The course review recommended unlocking after the charge. ADR-025 is decided (15 November 2026) and built (Slice 3 PR-7) for a declined first invoice only; the upgrade's order still needs a decision. |
| D-10 | Saga countermeasures | Partly built ◆ | Not started | High | — | The case's row lock (CollectionCases.lockCaseWithDebt, lock); CaseSettlement.settleWithInvoicing; case_step and case_retry guards; renew(n) and Subscription's version | Built (Slice 3): a pessimistic lock orders a decline, InvoicePaid and a timer on one case; every timer re-reads Invoicing before it changes the state (reread value); each step and retry is guarded once. Not built: a semantic lock state on Subscription. |
| D-11 | Event-carried state transfer vs notification | Designed | Designed | High | D13 | ADR-016 (events carry what the listener needs); ADR-012 (compacted access facts) | — |
| D-12 | Optimistic locking on aggregates | Built ◆ | Not started | High | D1 | @Version on Subscription, Invoice, CollectionRequest, CollectionCase | — |
| D-13 | Database constraints for invariants | Built ◆ | Not started | High | D1, D2, D3 | Named unique constraints, e.g. subscription_period_one_per_number, collection_request_one_per_reference, payment_attempt_one_per_key; CLAUDE.md rule 11 | — |
| D-14 | Partitioned renewal run (single writer) | Not started | Not started | Medium | D6 | ADR-003 claims batches; no partitioning | Not needed at D6's 14 a second. |
| D-15 | Clock as a dependency | Checked ◆ | Not started | High | D10 | platform/ClockConfiguration; ArchitectureRulesTest bans no-argument now() and currentTimeMillis | — |
| D-16 | Multi-tenancy model | Not applicable ◆ | Not started | High | — | Design Record §1: one streaming business billing households | Tally has no merchants or tenants. |
| D-17 | Event ordering per key | Checked ◆ | Not started | Medium | D13 | shared.DomainEvent carries the sequence (ADR-016; FF-13); accessfeed keeps one sequence per account, +1 only on change (AccessFeedIT); the relay sends one account at a time, each send a Kafka transaction (AccessFeedOrderIT, SpikeS4UnacknowledgedSendIT); Access applies only a higher sequence (AccessLogIT, AT21OldAndRepeatedMessagesIT) | — |
| D-18 | Read-your-own-writes | Checked | Designed | Medium | D13 | AT01SignUpHappyPathIT asks the Access service: canWatch within 5 s (769 ms in CI on #70); AccessFeedLatencyIT: sign-up to committed facts in the log in about 0.6 s | No app exists in this repository, so the "activating" screen is not built; NOT_KNOWN is what it would show. |
| D-19 | Bulkhead for provider calls | Built ◆ | Not started | Medium | D2 | ProviderIsolation: "The worker pool is the bulkhead" | — |
| D-20 | Dead-letter queue with alert | Partly built ◆ | Not started | Medium | D3, D9 | FAILED queue rows keep last_error and are never deleted (Retention); money queues carry money="true" on their health metrics (QueueDefinition.asMoneyQueue); tally.queue.failed; tally.events.unreadable | The queue table is the dead-letter store: a failed item waits there for a person. Missing: the alert rule on money queues (Slice 2 step 0 alerts brief). |
| D-21 | API composition | Designed ◆ | Not started | Low | — | Design Record §6: support view as a read model over published events | Not built. |
| D-22 | Catalogue cache by version | Not started | Not started | Low | — | — | — |
| D-23 | Reconciliation | Checked ◆ | Not started | High | D2, D14 | payment/application/Reconciliation (unknown resolution, ADR-026); SettlementReconciliation (proposed 021: settlement batches, both directions, V31); InvariantJobs with InvoicedVersusCollected (AT-12); UnknownResolutionIT, SettlementReconciliationIT; alert SettlementMismatch | A charge in a settlement batch resolves an UNKNOWN attempt; every other disagreement is a mismatch for a person (runbook settlement-mismatch.md). The fake's batch format is an assumption (CONTRACT.md A15) until a provider is chosen. |
| D-24 | RPO, RTO and tested restore | Designed ◆ | Not started | Medium | D8 | ADR-020: 7-day point-in-time recovery, 25-day snapshots, deletion log | No restore drill yet. |
| D-25 | Every active subscription has its next renewal scheduled (invariant check) | Checked ◆ | New | High | D1, D9 | subscription/adapter/persistence/SubscriptionInvariants (check subscription.next-renewal-scheduled, a daily job in subscription.jobs, V30); NextRenewalScheduledIT | Counts subscriptions with no unfinished renewal row for the period after their latest. A FAILED row counts as scheduled, because tally.queue.failed already reports it. Reads the renewal payload format written by Renewals.scheduleNext. |
| D-26 | Renewal queue key per subscription | Checked ◆ | New | Medium | D1 | Renewals.scheduleNext keys a renewal as <account>:<subscription>:<period end> (Slice 3 PR-2); SubscriptionApiIT.anAccountWithAnEndedSubscriptionSignsUpAgain | Fixed in Slice 3 PR-2, when A-14's limit became one live subscription per account: an account whose subscription ended may sign up again on the same billing day, while the ended one's renewal row still waits, so a key per account would drop the new subscription's renewal. The test signs up again on the same day and finds both rows. Rows written before keep the old key; the period's unique constraint still guards them. |
| D-27 | Command to record a person's decision on an unknown payment past the key window | Partly built ◆ | New | High | D2 | tally.payment.unknown_past_key_window gauge (Reconciliation); docs/runbooks/unknown-payment-past-key-window.md | Design Record lists it as required before production. The alert and the runbook exist. The command does not: the runbook says to change the database by hand. |

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
| D2-08 | Distributed data access patterns | Checked | Designed | Medium | D13 | Access holds a replicated copy of the access facts, fed by the event log (ADR-012, ADR-033); AT18AccessSurvivesBillingIT answers from it with billing and its database stopped | — |
| D2-09 | Contracts and stamp coupling | Built ◆ | Not started | Medium | — | AccessFacts v1 carries only what Access reads: IDs, enums, numbers and times (contracts/access-facts/v1/consumer-access.json; ConsumerAccessFieldsTest; F7AccessFactsContractTest) | Reviewed for the one message that crosses quanta. No test fails if billing sends a field Access does not read; billing's in-process events are not reviewed against their consumers. |
| D2-10 | Saga participation marked in code | Not started | Not started | Low | — | — | — |
| D2-11 | Granularity of the tax adapter | Designed | Designed | Low | D12 | FR-016 kept: tax bought behind the TaxRates port in Invoicing | — |

## E Evolution and governance

| ID | Topic | Status | Was | Priority | Drivers | Evidence | Note |
|---|---|---|---|---|---|---|---|
| E-01 | Architecture decision records | Built | Built | High | — | docs/architecture/v2/README.md; accepted text never edited | — |
| E-02 | ADR template v2 | Built ◆ | Not started | High | — | ADR-001 has status, context, decision, consequences, compliance, alternatives rejected and revisit-when; the log has Drivers and Checked-by columns | Only an "advice sought" line is missing. |
| E-03 | Fitness functions | Checked ◆ | Built | High | — | tally/src/test/java/com/tally/architecture (F3, F8–F13, FF-12, FF-13, pinned events and listeners) | — |
| E-04 | Holistic and continual fitness functions | Partly built ◆ | Not started | High | D1, D6 | .github/workflows/nightly.yml runs AT-9 (5,000 accounts) and AT-10 (10,000 renewals) | Not yet 1,000,000 (needs staging). No continual check in production. |
| E-05 | Expand and contract | Partly built ◆ | Designed | High | D5 | ADR-009; DestructiveMigrationsTest and DestructiveStatements (see E-06) | A destructive statement now needs a written reason, checked by the build. The check cannot tell whether the expand release really shipped first: a reviewer judges the reason. No change has yet been made in two releases (V16, V20, V27, V28 were one-step, safe only because no production database existed). The N-1 replay check ADR-009 also names does not exist. |
| E-06 | Migration check for destructive changes | Checked ◆ | Not started | High | D5 | architecture/DestructiveMigrationsTest and DestructiveStatements, in ./mvnw verify: drop table, schema, view or column, rename, change of column type, set not null, a new not-null column without default, truncate. Each needs '-- contract: <reason>' above it. V1-V30 findings are pinned exactly, because a marker would change their Flyway checksum | Built as a test instead of Squawk: no new tool, and it runs where every other fitness function runs. It does not flag lock-heavy changes such as an index built without CONCURRENTLY, which Squawk would. |
| E-07 | Tolerant reader and event versioning | Checked | Checked | High | D3, D13 | ADR-016, ADR-024, ADR-028; EventFieldsMayBeMissingTest (FF-13); EnumsHaveUnknownDefaultTest (FF-12) | — |
| E-08 | Upcasting old events | Not applicable ◆ | Not started | Medium | — | CLAUDE.md rule 7 and FF-13: added fields are nullable, readers treat missing as not known | The add-only rule removes the need for upcasting so far. |
| E-09 | Contract-first API | Checked | Checked | Medium | — | docs/api/openapi.yaml; ContractIT (ADR-019) | — |
| E-10 | Breaking-change check (oasdiff) | Not started | Not started | Medium | — | — | — |
| E-11 | Consumer-driven contracts | Checked | Designed | Medium | D13 | contracts/access-facts/v1/ (ADR-034): F7AccessFactsContractTest fails billing's commit stage on a broken field Access reads; Access's ExamplesTest reads every example, and ConsumerAccessFieldsTest keeps consumer-access.json equal to what Access reads | — |
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
| H-03 | Renewal run: one durable timer per subscription, competing workers | Checked ◆ | Partly built | High | D1, D6, D9 | Renewals.scheduleNext enqueues the next renewal inside the renewal transaction, due at 00:00 in the account time zone (BillingCalendar.billingTime); SKIP LOCKED claims, 5-minute lease, reaper, claim token (JdbcDurableQueue); guard subscription_period_one_per_number checked at start-up (DuplicateGuardCheck); DurableQueueIT; AT-9; AT-10 | Each renewal schedules the next one; there is no daily scan. The course review advised a 00:00 scan that enqueues the day; that does not apply. See D-25 for the check this design needs. |
| H-04 | Capacity sizing (Little's law) and pre-scaling | Partly built ◆ | Not started | Medium | D6 | D6: about 14 a second; AT-10 throughput test | Renewals fall due at local midnight per account, so load peaks per time zone, not at one UTC instant. No pre-scaling; no deployment. |
| H-05 | Connection sizing and PgBouncer | Not started | Not started | Medium | D6 | — | — |
| H-06 | Probes and graceful shutdown | Partly built ◆ | Not started | High | D9 | QueueRuntime stops pollers on shutdown (fix from the Slice 2 spike); actuator health exposed | No separate liveness and readiness groups. |
| H-07 | Front door and rate limits | Not started | Not started | Medium | — | Outbound provider rate limit is built (ProviderIsolation); nothing inbound | — |
| H-08 | One layer owns retries | Designed | Designed | Low | — | Course review only; the Design Record has no service mesh | Retries live in Payment (ADR-013). |
| H-09 | Slice 2 messaging choice | Built | Designed | High | D13 | Kafka 4.2.1 (spike S-1): the compacted topic access-facts, one transaction per send, read_committed (ADR-033); SpikeS2CompactionIT, SpikeS4UnacknowledgedSendIT; infra/docker-compose.yml | The managed service is not chosen. It must support Kafka transactions, read_committed and the broker's refusal of a late write after an abort (ADR-033); S-4 experiment 3 is the test to run against a candidate. |
| H-10 | Multi-AZ deployment | Designed ◆ | Not started | Medium | D1–D3 | ADR-007: synchronous standby; Design Record §13: 2+ copies | — |
| H-11 | Multi-region triggers | Not started | Not started | Low | — | Constraint C4: personal data stays in the region | — |

## I Security

| ID | Topic | Status | Was | Priority | Drivers | Evidence | Note |
|---|---|---|---|---|---|---|---|
| I-01 | Threat model | Not started | Not started | High | C2, D8 | Design Record §15 risk register is not a threat model | — |
| I-02 | API authentication (subscriber app, back office) | Checked ◆ | Designed | High | D2, D13 | ApiSecurity (billing) and AccessSecurity (Access): a signed token on every `/v1/` request, signature, issuer, audience and expiry checked by Tally, the expiry on the injected clock; keys per caller (`user:<id>`); AT-26, AT-29, AT-30; ApiTokensRefused, IdentityKeysUnavailable and their Access pair, with promtool tests | Built in Slice 4 (#89, #91, #92, #93). The streaming platform calls Access with a service token (decision 1, option A). Staff roles, and the back office, come with the first back-office endpoint. No identity provider product is chosen. |
| I-03 | Human login via OIDC | Not started | Not started | Medium | — | — | — |
| I-04 | Object-level authorisation + cross-account test | Checked ◆ | Designed | High | — | `account.account_user` (V43, `account_user_one_per_user`); `platform.Caller`, found once for each request; each controller answers 404 for another household; AT-27, AT-28; F16 `everyApiHandlerTakesACaller` | Built in Slice 4 (#89, #90, #91). 404, never 403, and nothing changes. Accounts opened before V43 have no link (no production data). |
| I-05 | One database user per module | Not started | Not started | High | — | — | Was "row-level security + DB user per module". Row-level security for tenants does not apply. |
| I-06 | RBAC and four-eyes refunds | Not started | Not started | Medium | — | — | — |
| I-07 | PCI scope: hosted fields | Built ◆ | Designed | High | C2 | FR-015; fake-provider ProviderController simulates the hosted card form | No log-scanning test for card numbers. |
| I-08 | Inbound webhook verification | Not applicable ◆ | Not started | High | D2 | ADR-026: Tally asks the provider by reference; no webhooks are consumed | Becomes relevant if a provider pushes outcomes. |
| I-09 | Outbound webhooks | Not applicable ◆ | Not started | Medium | — | No merchant callbacks in Tally | — |
| I-10 | Secrets and keys | Not started | Not started | Medium | — | application.yml holds local credentials only | — |
| I-11 | No personal data in events | Checked ◆ | Not started | Medium | D8 | F15PayloadScanTest: the AccessFacts schema allows no extra properties, and every property is an ID, an enum, a number or a time; it fails on a free-text field (shown in #67) | Covers the message that leaves billing. In-process events stay in billing's database. |
| I-12 | Audit log | Partly built ◆ | Not started | Medium | D7 | Each invoice stores the PriceQuote reason as data (QuoteReason, V19) | Traces charges (D7). No audit of staff actions. |
| I-13 | Supply chain | Not started | Not started | Low | — | — | — |

◆ marks a status changed by this check.

## Keeping it current

Change a row in the pull request that changes its status, and name the evidence. A row reaches Checked only when a test that fails the build exists; never for a check that is only planned (CLAUDE.md, slice report item 6). Keep the CSV and this file in step.
