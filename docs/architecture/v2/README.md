# Tally decision log, second design

The architecture was designed a second time, from zero, in sixteen steps. The full reasoning, options and rejected alternatives live in the **Tally Design Record** (outside this repository). This file summarises the decisions the code must follow.

Rules for this log are unchanged from the first run: one decision per record; an accepted decision's text is never edited, only superseded.

First-run records are archived under `docs/architecture/archive/first-run/` as `FR-NNN`. The mapping at the end says what happened to each.

## Decisions

| # | Decision | Drivers | Checked by |
|---|---|---|---|
| **Style and structure** | | | |
| 001 | Billing is one modular monolith with one database. Access is a separate quantum with its own copy of who may watch. *(Access extraction: Slice 2.)* | D1, D3, D13, G9 | Pinned graph; Access-survives-billing test (Slice 2) |
| §10 | One module per context; small public API in the base package; downstream depends on upstream's API; Payment depends on `shared` only | the context map; R1 | F1, F2 |
| §10 C | Ports and adapters inside `offers`, `subscription`, `invoicing`, `payment`; closed layers inside `account`, `collections`, `support`; `jakarta.persistence` allowed on aggregates | D2, D10, D11, D12 | F8, F9, F10 |
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

§-numbers refer to sections of the Design Record where the decision has no separate ADR number.

## Proposed: imported from the first run

The second design missed these. They are proposed here, to be confirmed in the Design Record.

| # | Proposal | From |
|---|---|---|
| 021 | Daily reconciliation against the provider's settlement report, both directions; every mismatch recorded and alerted (D14) | FR-031 |
| 022 | What happens to an invoice and to access when a recorded success proves false; needs finance | FR-032 |
| 023 | Renewals are merchant-initiated; an authentication demand is a distinct outcome and reaches the customer (D15) | first-run D15 |
| 024 | Where UNKNOWN is a real state of a shared enum, a value the reader does not know maps to UNRECOGNISED, never to UNKNOWN; amends 016 | Slice 1R PR #13 review (`AttemptState`) |
| 025 | A decline on the first invoice ends the subscription at once; for finance and product, not in Slice 1R | Slice 1R PR #13 review (access at sign-up) |
| 026 | How an unknown payment is resolved: a charge found by reference decides; "not found" is no charge only from a consistent lookup, after the call timeout plus the provider's stated delay, else it stays UNKNOWN; once each attempt has its own key, a repeat by key only within the provider's retention minus a margin, else the alert and settlement reconciliation (021). Only a SENT attempt becomes UNKNOWN by age; a REQUESTED attempt was never sent. Before Collections retries a declined reference, Payment looks the reference up. If the provider shows any charge for it, no new attempt is made, and the declined attempt is corrected to SUCCEEDED. Amends 004, 018 and the payment state machine | Slice 1R PR #14, #15 and #20 reviews; spike S-2 |

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
