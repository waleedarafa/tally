# Slice 1 brief: the walking skeleton

## Goal
A customer subscribes, is invoiced, is charged through the fake provider, and gets access. Renewals then run from the database queue, safely, on two instances. The structure and its guards are built before the features.

## In scope
- Accounts, a fixed catalogue, subscribe, first invoice, payment, access.
- Monthly renewals through the billing schedule queue.
- Provider isolation: timeouts, rate limit, circuit breaker, bulkhead.
- `UNKNOWN` outcomes and reconciliation.
- Event resubmission, invariant checks, health signals for every job and queue.
- Fitness functions and a CI pipeline.
- Two spikes (section 9).

## Out of scope (later slices)
Plan changes, cancellation and proration (4). Promotions (3). Idempotency keys and retries (2). Dunning (5). Tax and data deletion (6). Finance export (4). Key rotation. Message broker (7). Any user interface. Deployment to staging and production is a stretch goal; CI is required.

## 1. Domain rules for slice 1
- **Catalogue:** three monthly plans (`BASIC`, `STANDARD`, `PREMIUM`), each with a price in AED and USD. Seeded by a Flyway migration.
- **Account:** currency, IANA time zone, billing day of month. One subscription per account in this slice.
- **Billing day:** the local calendar day the subscription starts. If a month is shorter, bill on its last day (31 → 30 April, 28 or 29 February).
- **Billing time:** 00:00 local time on the billing date, stored as an `Instant` in `due_at`.
- **First invoice:** issued at subscribe, for one full month. No proration. No tax.
- **Invoice states:** `PAYMENT_PENDING`, `PAID`, `PAYMENT_FAILED`.
- **Attempt states:** `PENDING`, `IN_FLIGHT`, `SUCCEEDED`, `FAILED` (with reason `SOFT` or `HARD`), `UNKNOWN`. At most one open attempt per reference (partial unique index).
- **Failed payment:** recorded only. No retries, no blocking. Access is unchanged.
- **Money:** `shared.Money(long minorUnits, Currency currency)`.

## 2. Contracts in this slice

| Module | Public API | Events published |
|---|---|---|
| `catalog` | `CatalogApi.quote(accountId, planId, period)` → `PriceQuote` | — |
| `account` | `AccountApi.open(...)`, `AccountApi.get(id)` → `AccountView` | — |
| `subscription` | `SubscriptionApi.subscribe(accountId, planId)`, `timeline(id)` | `SubscriptionChanged` (thin: IDs, version, type) |
| `invoicing` | `InvoicingApi.invoicesFor(accountId)` | `InvoiceIssued`, `InvoicePaid` |
| `payment` | `PaymentApi.requestCollection(reference, accountId, money, token)`, `PaymentApi.status(reference)` | `PaymentSucceeded`, `PaymentFailed` (reason `SOFT`, `HARD`, `UNKNOWN`) |
| `entitlement` | `EntitlementApi.hasAccess(accountId)` | — |

**REST (`/v1`):** `POST /accounts`, `POST /subscriptions` (account, plan, payment token), `GET /payments/{reference}/status` (reads from Payment), `GET /accounts/{id}/access`.

## 3. Proposed records: build them switchable
ADR-019 is still Proposed. Implement it behind settings, so the decision can change without code changes:
- `tally.checkout.payment-wait` (default `3s`): how long checkout waits for a payment result before returning `PROCESSING`.
- `tally.access.grant-before-payment` (default `true`): grant access on `SubscriptionChanged(STARTED)`, or only on the first `PaymentSucceeded`.

ADR-011 and ADR-021 are out of scope for this slice.

## 4. Queue infrastructure (ADR-017)
Build it once, in a `platform` module. It holds infrastructure only, never business logic.
- `DurableQueue`: enqueue (key, due time, payload), register a handler, claim a batch with `FOR UPDATE SKIP LOCKED`, mark done or failed.
- Each module owns its queue tables in its own schema.
- A reaper returns rows `IN_PROCESSING` longer than the timeout.
- **Periodic jobs** (resubmission, reconciliation, invariant checks) are rows that reschedule themselves after each run. This keeps "no `@Scheduled`" true everywhere.
- Pollers are the only place that owns threads for this. A fitness function forbids `@Scheduled` outside `platform`.
- Health per queue and job (ADR-023), as Micrometer metrics:
  - `tally.queue.oldest_due_age_seconds{queue}`
  - `tally.queue.waiting{queue}`
  - `tally.job.last_success_age_seconds{job}`
  - `tally.invariant.violations{check}`
  - `tally.events.incomplete{}` and `tally.events.oldest_incomplete_age_seconds{}`

## 5. Payment execution (ADR-005, ADR-018)
- Workers claim `PENDING` attempts. They call the provider with no transaction open.
- Timeouts: 1 s connect, 5 s read.
- Per-instance rate limit: provider limit ÷ instance count (setting). A 429 response returns the attempt to `PENDING` with a delay. It is not a failure.
- Circuit breaker: while open, workers stop claiming.
- Separate worker pool (default 15 threads).
- Timeout → `UNKNOWN`, never retried.
- Reconciliation job checks `UNKNOWN` attempts, **and** `FAILED` attempts from the last 48 hours, against `GET /charges?reference=`. Backoff for `UNKNOWN`: 5 min, 1 h, then daily. A corrected outcome publishes the matching event and increments `tally.payment.outcome_corrected`.
- Libraries: use Resilience4j if a version compatible with Spring Boot 4 exists. Otherwise use its core modules directly. Check before choosing; report the choice.

## 6. Fake provider (ADR-020)
A separate Spring Boot application.
- `POST /tokens` → token (simulates the hosted card form, ADR-015).
- `POST /charges` (token, amount, currency, reference) → charge ID and outcome.
- `GET /charges?reference=` → the recorded truth.
- `POST /admin/mode`, combinable modes: `NORMAL`, `SLOW(ms)`, `TIMEOUT_AFTER_CHARGE` (records the charge, then delays past the client timeout), `OUTAGE` (503), `RATE_LIMIT(n per second)` (429 above it), `DECLINE_SOFT(%)`, `DECLINE_HARD(%)`, `LIE` (the `POST` response reports the opposite of the recorded outcome).
- `GET /admin/charges/count?reference=`: lets tests prove nobody was charged twice.

## 7. Build order
Each step ends with `./mvnw verify` green.

1. **Skeleton.** Parent `pom.xml`, `tally` and `fake-provider` applications, Maven wrapper, Docker Compose for local PostgreSQL, a Testcontainers base test. Empty modules with `package-info.java`.
2. **Fitness functions first.** Spring Modulith verification. ArchUnit: no cross-module JPA relations; no direct `now()`; no `@Scheduled` outside `platform`; nothing in `payment` imports `invoicing`; `domain` does not depend on adapters; pinned event class list; every registered job has a health signal. **Prove each rule bites:** add a deliberate violation, see the build fail, then remove it.
3. **CI.** GitHub Actions runs `verify` on every push, and builds one container image per commit tagged with the commit ID.
4. **Platform.** `Clock` bean and a test clock that can be advanced. `DurableQueue`, reaper, periodic jobs, health metrics.
5. **Thin path.** Account, catalogue, subscribe, first invoice, collection request, worker, provider, events back, invoice paid, access granted, checkout and status endpoints.
6. **Renewals.** Billing schedule, next-date scheduling, clamping, time zones.
7. **Provider isolation.** Timeouts, rate limit, breaker, bulkhead, `UNKNOWN`, reconciliation.
8. **Background safety.** Event resubmission, invariant checks, health for everything. Run both spikes here.
9. **Chaos and load.** Two instances, kill one; scaled load test.

## 8. Acceptance tests

| ID | Scenario | Pass when | Driver |
|---|---|---|---|
| AT-1 | Checkout, happy path | Invoice `PAID`, access granted, response shows `SUCCEEDED` within 3 s | D3, ADR-019 |
| AT-2 | One month of time travel, 100 accounts | Exactly one new invoice and one attempt per account; test < 2 min | D1, D4 |
| AT-3 | Calendar edges | Day 31 bills on 30 April and 28/29 February; local midnight in a non-UTC zone; correct across a daylight-saving change | D4 |
| AT-4 | Provider outage mid-run | Breaker opens; no claims while open; invoices still created; all charged after recovery; checkout returns `PROCESSING` | D3, D7 |
| AT-5 | Timeout after charge | Attempt `UNKNOWN`, not retried; reconciled to `SUCCEEDED`; invoice `PAID`; provider charge count = 1 | D3 |
| AT-6 | Lying provider | Reconciliation corrects the outcome; `outcome_corrected` increments | D3 |
| AT-7 | Crash after invoice commit, before listener | Collection requested exactly once after recovery | D2 |
| AT-8 | Listener fails | Resubmitted within the configured limit; effect exactly once | D2 |
| AT-9 | Two instances, kill one mid-run | Exactly one invoice per account and date (5,000 accounts in CI; 1,000,000 in staging later) | D1 |
| AT-10 | Throughput, scaled | 2,000 renewals in CI (10,000 nightly), provider at 100/s: no 429 storm, all first attempts made | D6 |
| AT-11 | Stop each worker and job | Its metric shows overdue within the threshold | D7, D13 |
| AT-12 | Invariant check | An injected duplicate invoice raises `invariant.violations` | D1, ADR-024 |

## 9. Spikes
**Spike A: what does Spring Modulith really do?** (ADR-022, risk score 9)
1. After a crash between commit and listener execution, what state is the publication in? How is it resubmitted, with and without `republish-outstanding-events-on-restart`?
2. After a listener throws, what state is the publication in? Does `IncompleteEventPublications` resubmit it?
3. If two instances resubmit at the same time, can the same publication run twice? (The resubmission job runs through the queue, so only one instance should claim it. Verify.)

**Spike B: does reconciliation find the truth?** Run `TIMEOUT_AFTER_CHARGE` and `LIE` modes, with outcomes in both directions. Measure time to resolution.

Write findings with evidence. If behaviour differs from ADR-022's assumptions, stop and report before continuing.

## 10. Definition of done
- AT-1 to AT-12 pass in CI.
- Every fitness function is green, and each was shown to fail on a violation.
- Both spike reports are written.
- All metrics in section 4 are exposed.
- `docs/briefs/slice-1-report.md` is written (format in `CLAUDE.md`).
- No Accepted decision record was edited.
