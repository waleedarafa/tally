# Tally decision log, second design

The architecture was designed a second time, from zero, in sixteen steps. The full reasoning, options and rejected alternatives live in the **Tally Design Record**, [`design-record.md`](design-record.md) (a snapshot of the document where it is written, refreshed at the end of every step and slice). This file summarises the decisions the code must follow.

Rules for this log are unchanged from the first run: one decision per record; an accepted decision's text is never edited, only superseded.

First-run records are archived under `docs/architecture/archive/first-run/` as `FR-NNN`. The mapping at the end says what happened to each.

## Decisions

| # | Decision | Drivers | Checked by |
|---|---|---|---|
| **Style and structure** | | | |
| 001 | Billing is one modular monolith with one database. Access is a separate quantum with its own copy of who may watch. *(Access extraction: Slice 2.)* | D1, D3, D13, G9 | Pinned graph; Access-survives-billing test (Slice 2) |
| §10 | One module per context; small public API in the base package; downstream depends on upstream's API; Payment depends on `shared` and `platform` only | the context map; R1 | F1, F2 |
| §10 C | Ports and adapters inside `offers`, `subscription`, `invoicing`, `payment`; closed layers inside `account`, `collections`, `support`; `jakarta.persistence` allowed on aggregates | D2, D10, D11, D12 | F8, F9, F10, F11 |
| §12 | Aggregates: Offer, OfferKind, Subscription, Invoice, CreditNote, Payment. Subscription owns periods through `renew(n)`; invoice ID = subscription + period; Payment ID = reference | D1, D2, D3 | Aggregate tests |
| **Runtime and data** | | | |
| 002 | Events inside billing use the event publication registry (an outbox); listeners are idempotent through business keys | D3 | AT-7, AT-8 |
| 003 | No leader for the billing run; copies claim batches with `FOR UPDATE SKIP LOCKED`; `renew(n)` makes a repeat harmless | D1, D6, D9 | AT-9 |
| 004 | Payment writes the attempt and key first, calls outside any transaction, records unknown on timeout, resolves unknowns by key | D2 | AT-5 |
| 005 | Renewal is choreographed by events; Collections is orchestrated by its own state machine with timers | flow shape | State-machine tests |
| 006 | Billing to Access through a managed event log, keyed by account, IDs only, no personal data *(Slice 2)* | D13, D8 | Event payload scan |
| 007 | Managed PostgreSQL with a synchronous standby; Access has its own small PostgreSQL | D1 to D3, G9 | Failover test before launch |
| 012 | Access rebuilds from a compacted topic of current access facts per account; amends 006 *(Slice 2)* | D13 | Rebuild test |
| 013 | Payment outcomes: succeeded, declined, technical failure, unknown. Technical failures retry with back-off and never start Collections; a breaker pauses collection | G6, D2 | AT-4, transition test |
| 014 | New subscribers may wait seconds for access; target 99% within 5 seconds; the app shows "activating" | G6 | AT-1 |
| **Delivery** | | | |
| 008 | Trunk-based; one pipeline per deployable; one image promoted through every stage | D5 | Pipeline configuration |
| 009 | Rolling deploys; expand and contract for schemas; add-only events | D5, G9 | Migration check; N−1 replay |
| 010 | Feature flags per account or country, recorded in the PriceQuote reason, logged, removed within 30 days | O3, D7, G7 | Flag report |
| 011 | Consumer-driven contracts for billing-to-Access events | D13 | Contract tests |
| **Detailed design** | | | |
| 015 | One `PricingRule` per kind of offer, found by a registry; start-up check; golden tests per rule | D5, O3 | Start-up check; golden tests |
| 016 | One event envelope (event ID, time, account ID, sequence); events carry what the listener needs; add-only; `UNKNOWN` in every shared enum; resubmission isolates each publication | D3, D13 | FF-12; AT-17 |
| 017 | Money in ISO minor units plus exact `Ratio`; rounding half up only in `times(Ratio)`; largest-remainder allocation | G4, R5 | Golden tests; F4 |
| 018 | Lifecycles as an enum and transition table inside each aggregate; a stale requested or sent attempt becomes unknown | D2, P1, P2 | Exhaustive table tests; AT-15 |
| 019 | REST: client `Idempotency-Key` stored before acting; RFC 9457 problem details; version in the path; OpenAPI first | D2 | AT-13; contract tests |
| 020 | Backups: 7-day point-in-time recovery, 25-day snapshots; a deletion log replayed after any restore | D8 | Restore drill |
| **Accepted in Slice 1R** | | | |
| 024 | Where UNKNOWN is a real state of a shared enum, a value the reader does not know maps to UNRECOGNISED, never to UNKNOWN; amends 016 | D3, D13 | FF-12 (`EnumsHaveUnknownDefaultTest`); `UnknownEnumValuesIT` |
| 026 | How an unknown payment is resolved, in order: (a) look the reference up; for the latest attempt of the reference, a charge found decides, and a decline found decides an UNKNOWN first attempt; an earlier attempt learns nothing from the lookup, which also shows its successors' charges (a superseded decline stops being re-checked; an earlier UNKNOWN goes on to b); (b) if nothing decides and the attempt's own key is younger than the provider's retention minus a margin, repeat with the key and the stored parameters; the outcome decides; (c) else never repeat: it stays UNKNOWN, with the alert, for settlement reconciliation (021). "Not found" alone never means "no charge". Only a SENT attempt becomes UNKNOWN by age; a REQUESTED attempt was never sent. Before Collections retries a declined reference, Payment looks the reference up. If the provider shows any charge for it, no new attempt is made, and the declined attempt is corrected to SUCCEEDED. Amends 004, 018 and the payment state machine | D2 | `UnknownResolutionIT`; `KeyRepeatWindowTest`; `PaymentAttemptTransitionsTest`; `ReconciliationIT`; AT-15; AT-16 |
| 027 | Idempotency keys: every `Idempotency-Key` is a UUID, or the request is refused with 400 and nothing stored. Keys are held by `platform`, in one table per module; a key is claimed before acting; the response is stored in the change's own transaction; a key whose request has not answered within the request timeout is taken over. Keys are scoped per account until callers are authenticated, then per caller. Extends 019 | D2 | AT-13; `SignUpRecoveryIT`; `ContractIT` |
| 028 | A listener is pinned like an event class: its identity (class, method, parameter types) is stored with each pending publication, so it is never renamed or moved while it may have incomplete publications. To move one: deploy the new listener, wait until the old one has none, then remove it. Extends 016 | D3 | `PinnedListenersTest` |
| **Accepted in Slice 2** | | | |
| 029 | Test isolation. No test class meets a row it did not create. Every test class, and every set of instances a test starts by hand, gets its own PostgreSQL database copied from a template migrated once per run (`CREATE DATABASE ... TEMPLATE`). A test that needs instances competing for rows starts them on one database it owns (AT-9, AT-10, `PeriodicJobIT`). A test that waits on a periodic job moves its clock to the job's next run; no test rewrites a job row to make it due | D10 (30 days of renewal and failed payment in a test, under a minute); the suite's reliability | F13 (`F13NoTestMakesWorkDueTest`); `PerContextDatabase` |
| 030 | Alert rules as code. Prometheus alerting rules live in the repository (`ops/alerts/tally.rules.yml`); every rule names a severity (page or ticket), a driver and a runbook; every rule has a `promtool` unit test where it fires and one where it stays quiet (`ops/alerts/tally.rules.test.yml`). D9 is measured as progress (work due and nothing finished), not as age. Where rules are evaluated and who is paged is decided with the first deployed environment | D9 (on-call alerted within 5 minutes of a stalled billing run); D2, D3, D6 | CI commit stage, step "Alert rules" (`promtool check rules`, `test rules`); F14 (`F14AlertRulesAgreeIT`) |

§-numbers refer to sections of the Design Record where the decision has no separate ADR number.

## Proposed

The second design missed these, or Slice 1R raised them. They are proposed here, to be confirmed in the Design Record. 024 and 026 were accepted in Slice 1R, and 029 and 030 in Slice 2; they moved to the decision table.

| # | Proposal | From |
|---|---|---|
| 021 | Daily reconciliation against the provider's settlement report, both directions; every mismatch recorded and alerted (D14). **Required before production** | FR-031 |
| 022 | What happens to an invoice and to access when a recorded success proves false; needs finance | FR-032 |
| 023 | Renewals are merchant-initiated; an authentication demand is a distinct outcome and reaches the customer (D15) | first-run D15 |
| 025 | A decline on the first invoice ends the subscription at once; for finance and product, not in Slice 1R | Slice 1R PR #13 review (access at sign-up) |
| 031 | Unknown-payment checks keep D2's hour (option B, as built in #52). An UNKNOWN attempt is checked at 5, 15, 30, 45 and 60 minutes after it became UNKNOWN, then hourly until its key window ends (20 hours after it was sent), then daily. Every time counts from when it became UNKNOWN, never from the last check, so a late check does not delay the ones after it; each check inside the window may repeat with the key (ADR-026 b). DECLINED attempts keep their schedule (5 minutes, 1 hour, then daily, for 48 hours). `PaymentUnknownTooLong` pages at 50 minutes. Replaces 5 minutes, 1 hour later, then daily, which broke D2 after one failed check and gave the key repeat two chances in 20 hours. Checked by `UnknownCheckScheduleIT`, `UnknownCheckScheduleTest` and the rule tests. **Required before production** | Slice 2 step 0 close (#50 review); Design Record, "Proposed decision, from step 0" |

## Mapping of first-run records

| First run | Status in the second design |
|---|---|
| FR-001 Modular monolith | Superseded by 001 (adds Access as a separate quantum) |
| FR-002 Invoicing owns the billing calendar | Superseded by §12: Subscription owns periods |
| FR-003 Module layout and enforcement | Kept; extended by §10 and §10 C |
| FR-004 | Already superseded |
| FR-005 Payment execution asynchronous and isolated | Kept; consistent with 004 |
| FR-006 Deployment platform and environments | Kept for infrastructure as code; topology per 007 and the deployment view |
| FR-007 Continuous deployment, rolling updates | Kept; same as 009 |
| FR-008 Team allocation and growth path | Superseded by Design Record §11 |
| FR-009 Contract and event rules | Kept; extended by 016 |
| FR-010 No broker until extraction | Superseded by 006 and 012 (Access is a separate quantum) |
| FR-011 Collections orchestrates dunning (proposed) | Accepted as 005 and §16 |
| FR-012 Build in-house; offers are the core | Kept |
| FR-013 Bounded contexts | Superseded: `catalog`→`offers`, `entitlement`→`access`, new `collections`, no `checkout` |
| FR-014 Context relationships | Superseded by `design.md` §3 |
| FR-015 Card numbers never touch Tally | Kept |
| FR-016 Tax bought behind an interface | Kept (the `TaxRates` port) |
| FR-017 Durable scheduled work on per-module queues | Kept; same as 003 |
| FR-018 Unknown outcomes reconciled | Kept; extended by 013 and 018 |
| FR-019 Access before first payment (proposed) | Resolved: trial access from sign-up (H7); 014 |
| FR-020 One repository; separate fake provider | Kept; Access becomes a second deployable in the same repository |
| FR-021 Consistency per fact (proposed) | Superseded by 014 and trade-off T3 |
| FR-022 Incomplete events resubmitted and monitored | Kept; same as 002 |
| FR-023 Every background job reports health | Kept |
| FR-024 Releasing changes that touch money | Kept; align with 010 (per account, never random percentages) |
| FR-025 Changes to shared data are additive | Kept; same as 009 |
| FR-026 | Already superseded |
| FR-027 Platform module for queues and jobs | Kept |
| FR-028 Event resubmission rules | Kept; extended by 016 (isolate each publication) |
| FR-029 Platform owns the listener executor | Kept |
| FR-030 Retry only when no charge is certain | Superseded by 013 |
| FR-031 Daily settlement reconciliation | Proposed as 021 |
| FR-032 Action on false success (proposed) | Proposed as 022 |
| FR-033 Invoiced versus collected checked in Invoicing | Kept |
| FR-034 Checkout is a process module | Superseded: sign-up through the Subscription REST API (019) |
| FR-035 Catalog may read Account | Kept, as `offers` reads `account` |
| FR-036 Provider rate target | Kept |
| FR-037 Retention of finished work | Kept |
| FR-038 Two-stage pipeline | Extended by 008 (commit, integration, staging, production, nightly) |
| FR-039 Dependency version policy | Kept |
| FR-040 Approved graph checked by a test | Kept; the graph is now `design.md` §3 |
| FR-041 One collection request per reference | Kept; Payment's ID is the reference |
