# Tally architecture decision log

One decision per record. An accepted record's decision text is never edited: it is superseded, with links both ways. Two edits are allowed: changing the status to Superseded, and adding a cross-reference to the Notes.
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
| [026](026-technology-constraints.md) | Technology constraints | Superseded by 039 | — | — |
| [039](039-dependency-version-policy.md) | Dependency version policy | Accepted | Constraint | Build configuration |
| [034](034-checkout-is-a-process-module.md) | Checkout is a process module | Accepted | D5 | FF-1, FF-2, FF-11 |
| [035](035-catalog-reads-account.md) | Catalog and Pricing may read Account | Accepted; revisit slice 3 | D5 | FF-11 |
| [040](040-approved-dependency-graph.md) | The approved dependency graph is checked by a test | Accepted | D5, D8, D9 | FF-11 |
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
| [028](028-event-resubmission-rules.md) | Event resubmission rules | Accepted | D1, D2, D3, D7 | Spike A test, `ConcurrentDeliveryIT` |
| [029](029-platform-owns-listener-executor.md) | The platform provides the event-listener executor | Accepted | D2, D7, D13 | FF-6, FF-10b |
| [030](030-retry-only-when-no-charge-is-certain.md) | Retry only when no charge is certain | Accepted; superseded in slice 2 | D3, D6 | Fake provider modes |
| [036](036-provider-rate-target.md) | Payment calls aim at 95% of the provider's limit | Accepted | D6 | AT-10 |
| [037](037-retention-of-finished-work.md) | Retention of finished work | Accepted (assumed period) | D6, D7, D11 | Job health, queue review |
| **Allocation** | | | | |
| [006](006-deployment-platform-and-environments.md) | Deployment platform and environments | Accepted | D6, D7, D12 | Infrastructure as code |
| [007](007-continuous-deployment-rolling-updates.md) | Continuous deployment with rolling updates | Accepted | D6, D7 | Pipeline, delivery metrics |
| [024](024-releasing-money-changes.md) | Releasing changes that touch money | Accepted | D3, D5, D10 | Business invariants |
| [025](025-shared-data-changes-additive.md) | Changes to shared data are additive | Accepted | D2, D7 | N−1 test (slice 3) |
| [038](038-two-stage-pipeline.md) | The pipeline has two stages | Accepted | D2, D6, D7 | Pipeline configuration |
| [008](008-team-allocation-and-growth-path.md) | Team allocation and growth path | Accepted | Fast flow, D5 | Per-team delivery metrics |
| [020](020-single-repository.md) | One repository; separate fake provider | Accepted | D3, D6, D8 | Manual |
| **Communication and data** | | | | |
| [009](009-contract-and-event-rules.md) | Contract and event rules | Accepted | D2, D5, D10 | Pinned event class test |
| [010](010-no-broker-until-extraction.md) | No broker until an extraction is accepted | Accepted | D2 | Manual |
| [011](011-collections-orchestrates-dunning.md) | Collections orchestrates dunning | **Proposed** | D1, D3, D7, D13 | State machine tests |
| [021](021-consistency-choice-per-fact.md) | Consistency chosen per fact | **Proposed** | D3, D10 | Failover test, reconciliation |
| [031](031-settlement-reconciliation.md) | Daily reconciliation against the provider's settlement report | Accepted (slice 2) | D3, D14 | Spike B test, reversal test |
| [032](032-action-on-false-success.md) | What happens when a reported success proves false | **Proposed** | D14 | With finance |
| [033](033-invoiced-versus-collected-in-invoicing.md) | Invoiced versus collected is checked inside Invoicing | Accepted | D10, D14 | Invariant check |

## Waiting for business approval

| Record | Who must approve | What they accept |
|---|---|---|
| 011 | Finance | The dunning policy and its customer impact |
| 019 | Product and finance | Free access until a failed payment is blocked |
| 021 | Product and finance | Letting customers in when Entitlement cannot answer |
| 032 | Finance | What a customer sees when a recorded payment proves false |

## Deferred decisions

| Decision | Trigger |
|---|---|
| Extract Payment (prefer a service-based split) | A Payments team with its own release rhythm; a separate scaling need; or the slice 7 experiment shows a clear gain |
| Add a message broker | An extraction is accepted |
| Split Catalog from Pricing | Their rates of change pull apart |
| Shared billing-timeline service (like Kill Bill's `junction`) | A second consumer needs the timeline |
| Promotion rules engine | Repeated promotions need new calculations |
| Shared rate limiter | More than two instances, or sustained 429 responses |
| Platform team | Visible friction: duplicated glue, teams waiting on pipeline fixes |
| One Maven module per context | Module boundaries stop moving |
| Notifications: shared context or separate ways | Slice 5, when Collections sends emails |
| Re-examine build versus buy | Billing products support our offer experiments |
| Provider notifications (webhooks) | Slice 2 idempotency is in place, and a real provider offers them |
| How renewals handle banks that demand customer authentication (D15) | Before a real provider is chosen; slice 2 design |

## Template

Title · Status · Context · Decision (with business justification) · Consequences · Compliance · Drivers · Revisit when · Notes
