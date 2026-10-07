# Tally architecture decision log

One decision per record. Accepted records are never edited: they are superseded, with links both ways.
This log was restructured once, in Phase 0, before any code existed. From now on the rules apply strictly.

## Records

| # | Decision | Status | Drivers | Checked by |
|---|---|---|---|---|
| **Strategy and domain** | | | | |
| [012](012-build-billing-in-house.md) | Build billing in-house; offers and pricing are the core | Accepted (course assumption) | Goals 1, 2 | Manual review per slice |
| [013](013-bounded-contexts.md) | Tally's bounded contexts | Accepted | D5, D8, D9 | Module tests |
| [014](014-context-relationships.md) | Context relationships | Accepted | D5, D8, D9 | ArchUnit, Modulith |
| [015](015-card-numbers-never-touch-tally.md) | Card numbers never touch Tally | Accepted | Constraint | Security review, log scan |
| [016](016-tax-bought-behind-interface.md) | Tax is bought, behind an interface | Accepted | D9 | Module tests |
| [026](026-technology-constraints.md) | Technology constraints | Accepted | Constraint | Build configuration |
| **Structure** | | | | |
| [001](001-modular-monolith.md) | Modular monolith | Accepted | D1, D2, D3, D5 | Modulith verification |
| [002](002-invoicing-owns-billing-calendar.md) | Invoicing owns the billing calendar | Accepted | D1, D4, D10 | ArchUnit |
| [003](003-module-layout-and-enforcement.md) | Module layout and enforcement | Accepted | D4, D5, D8, D9 | Four fitness functions |
| **Runtime** | | | | |
| [004](004-billing-schedule-database-queue.md) | Billing schedules on a database queue | Superseded by 017 | — | — |
| [017](017-durable-scheduled-work.md) | All durable scheduled work on per-module queues | Accepted | D1, D2, D4, D7, D13 | ArchUnit, chaos test |
| [005](005-payment-execution-asynchronous-isolated.md) | Payment execution is asynchronous and isolated | Accepted | D3, D6, D7, D8 | Fake provider tests |
| [018](018-unknown-payment-outcomes-reconciled.md) | Unknown outcomes are reconciled, never retried | Accepted; superseded in slice 2 | D3, D13 | Fake provider modes, alert |
| [019](019-access-before-first-payment.md) | Access before first payment; checkout waits then polls | **Proposed** | D3, D7 | Integration tests |
| [022](022-incomplete-event-publications-resubmitted.md) | Incomplete events are resubmitted and monitored | Accepted | D2, D7, D13 | Spike, permanent test |
| [023](023-background-job-health.md) | Every background job reports health | Accepted | D7, D13 | Fitness function, chaos |
| [027](027-platform-module-for-queues-and-jobs.md) | A platform module holds queue and job infrastructure | Accepted | D1, D2, D7, D13 | Modulith, ArchUnit |
| **Allocation** | | | | |
| [006](006-deployment-platform-and-environments.md) | Deployment platform and environments | Accepted | D6, D7, D12 | Infrastructure as code |
| [007](007-continuous-deployment-rolling-updates.md) | Continuous deployment with rolling updates | Accepted | D6, D7 | Pipeline, delivery metrics |
| [024](024-releasing-money-changes.md) | Releasing changes that touch money | Accepted | D3, D5, D10 | Business invariants |
| [025](025-shared-data-changes-additive.md) | Changes to shared data are additive | Accepted | D2, D7 | N−1 test (slice 3) |
| [008](008-team-allocation-and-growth-path.md) | Team allocation and growth path | Accepted | Fast flow, D5 | Per-team delivery metrics |
| [020](020-single-repository.md) | One repository; separate fake provider | Accepted | D3, D6, D8 | Manual |
| **Communication and data** | | | | |
| [009](009-contract-and-event-rules.md) | Contract and event rules | Accepted | D2, D5, D10 | Pinned event class test |
| [010](010-no-broker-until-extraction.md) | No broker until an extraction is accepted | Accepted | D2 | Manual |
| [011](011-collections-orchestrates-dunning.md) | Collections orchestrates dunning | **Proposed** | D1, D3, D7, D13 | State machine tests |
| [021](021-consistency-choice-per-fact.md) | Consistency chosen per fact | **Proposed** | D3, D10 | Failover test, reconciliation |

## Waiting for business approval

| Record | Who must approve | What they accept |
|---|---|---|
| 011 | Finance | The dunning policy and its customer impact |
| 019 | Product and finance | Free access until a failed payment is blocked |
| 021 | Product and finance | Letting customers in when Entitlement cannot answer |

## Deferred decisions

| Decision | Trigger |
|---|---|
| Extract Payment (prefer a service-based split) | A Payments team with its own release rhythm; a separate scaling need; or the slice 7 experiment shows a clear gain |
| Add a message broker | An extraction is accepted |
| Split Catalog from Pricing | Their rates of change pull apart |
| Shared billing-timeline service (like Kill Bill's `junction`) | A second consumer needs the timeline |
| Promotion rules engine | Repeated promotions need new calculations |
| Shared rate limiter | More than two instances, or frequent 429 responses |
| Platform team | Visible friction: duplicated glue, teams waiting on pipeline fixes |
| One Maven module per context | Module boundaries stop moving |
| Notifications: shared context or separate ways | Slice 5, when Collections sends emails |
| Re-examine build versus buy | Billing products support our offer experiments |

## Template

Title · Status · Context · Decision (with business justification) · Consequences · Compliance · Drivers · Revisit when · Notes
