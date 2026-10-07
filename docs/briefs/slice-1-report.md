# Slice 1 report: the walking skeleton

All nine build steps of `docs/briefs/slice-1.md` are done, on branch `claude/confident-faraday-npj6zc`. `./mvnw verify` is green locally (about 8 minutes) and in GitHub Actions.

Companion documents:
- `docs/briefs/slice-1-fitness-proof.md`: each fitness function shown failing on a deliberate violation.
- `docs/briefs/slice-1-spikes.md`: Spike A and Spike B, with full evidence.

**Definition of done (brief section 10)**

| Item | State |
|---|---|
| AT-1 to AT-12 pass in CI | Yes. All twelve pass in `./mvnw verify`, locally and in CI (section 1). |
| Every fitness function green, and each shown to fail on a violation | Yes (section 5, and the proof document). |
| Both spike reports written | Yes: `slice-1-spikes.md`. |
| All metrics in section 4 exposed | Yes, on `/actuator/metrics`, plus five additions (section 3, D5). |
| `slice-1-report.md` written | This document. |
| No Accepted decision record edited | Yes: nothing under `docs/architecture/` changed. Section 4 suggests new records instead. |

## 1. What was built, per acceptance test

| AT | Scenario | Test | Result | Evidence from the last full build |
|---|---|---|---|---|
| AT-1 | Checkout, happy path | `acceptance.AT01CheckoutHappyPathIT` | **Pass** | Over HTTP, against the fake provider in its own container, on production poll settings: `SUCCEEDED` in 877 ms (limit 3 s); invoice `PAID`; access granted; 1 charge. |
| AT-2 | One month of time travel, 100 accounts | `acceptance.AT02MonthOfTimeTravelIT` | **Pass** | 100 accounts across 5 time zones, 2 currencies and 3 plans. Exactly one new invoice, one new attempt and one charge per account. 9 s (limit 2 min); 35 s before the poller fix in step 9. |
| AT-3 | Calendar edges | `acceptance.AT03CalendarEdgesIT`, `invoicing.domain.BillingCalendarTest` | **Pass** | Real renewals from January 2026 to April 2028, 52 clock jumps. Anchor day 31 in New York billed on 28 Feb 2026, 30 Apr 2026 and 29 Feb 2028, always at local midnight through EST and EDT. Santiago billed at 01:00 local on 6 Sep 2026, the day midnight does not exist. |
| AT-4 | Provider outage mid-run | `acceptance.AT04ProviderOutageIT` | **Pass** | Breaker on production settings: all 20 renewal invoices were still created; the breaker opened; checkout answered `202 PROCESSING`; nothing was claimed while it was open (8 provider requests for 21 payments); after recovery, everything `PAID` with 1 charge each. |
| AT-5 | Timeout after charge | `acceptance.AT05TimeoutAfterChargeIT` | **Pass** | Attempt `UNKNOWN`; one request only, never retried. Reconciled to `SUCCEEDED` 5 minutes later; invoice `PAID`; 1 charge; `outcome_corrected{UNKNOWN,SUCCEEDED}` +1. |
| AT-6 | Lying provider | `acceptance.AT06LyingProviderIT` | **Pass** (one direction, see 2.B) | The provider charged but answered "declined": `FAILED` and `PAYMENT_FAILED`, corrected to `SUCCEEDED` and `PAID`; `outcome_corrected{FAILED,SUCCEEDED}` +1. |
| AT-7 | Crash after invoice commit, before the listener | `acceptance.AT07CrashBeforeListenerIT` | **Pass** | Two real application instances. A committed the invoice and its `InvoiceIssued` publication, then went away before the collection listener ran. B requested collection exactly once, 6 minutes later (business time); 1 attempt, 1 charge. |
| AT-8 | Listener fails | `acceptance.AT08ListenerFailsIT` | **Pass** | A real failure: no payment token yet. Once the token arrived, the publication was resubmitted and the invoice paid 6 minutes after the failure, which is the configured limit (5-minute age plus a 1-minute job interval). 1 attempt, 1 charge. |
| AT-9 | Two instances, kill one mid-run | `acceptance.AT09KillOneInstanceIT` | **Pass** | Two real Tally processes from the packaged jar, on the real clock, with 5,000 accounts. A was killed with SIGKILL at 518 invoices, holding 4 renewal and 8 collection claims. Exactly one invoice per account and date for all 5,000; one next date scheduled per account; one attempt per invoice. 5 attempts A had in flight are `UNKNOWN` and left to reconciliation. 56 s. |
| AT-10 | Throughput, scaled | `acceptance.AT10ThroughputIT` | **Pass** | Two real processes on production settings against a provider limited to 100 calls a second. 2,000 renewals charged in 32 s; 0 of 2,000 calls refused (CI threshold: 1%). Nightly size, run locally: 10,000 in 115 s (87 a second), 0.69% refused. |
| AT-11 | Stop each worker and job | `platform.internal.AT11StopEachWorkerAndJobIT` | **Pass** | All 16 jobs: 5 queue pollers (the workers), 5 reapers, reconciliation, event resubmission, 4 invariant checks. None overdue while running or before its threshold; each overdue just past it (5 minutes, or 2 days for the daily checks). A payment waiting in a stopped queue shows `tally.queue.overdue` after 5 minutes. |
| AT-12 | Invariant check | `acceptance.AT12InvariantCheckIT` | **Pass** | An injected overlapping invoice raised `tally.invariant.violations{check="invoicing.duplicate-invoices"}` at the next daily run. |

**Built per step**, with commits on the branch:
1. **Skeleton:** parent pom (Java 25, Spring Boot 4.1.1, Spring Modulith 2.1.1), the `tally` and `fake-provider` applications, the Maven wrapper, Docker Compose, a Testcontainers base test, and eight modules with declared dependencies.
2. **Fitness functions:** section 5.
3. **CI:** GitHub Actions runs `verify` on every push, then builds the `tally` image and pushes it to GHCR, tagged with the commit SHA. There is also a nightly load workflow.
4. **Platform:**
   - an injected `Clock`, and a `TestClock` for tests;
   - `DurableQueue`: per-module queue tables, idempotent enqueue, claims with `FOR UPDATE SKIP LOCKED` committed before work starts, completions checked against a claim token, a reaper;
   - pollers and worker pools;
   - self-rescheduling periodic jobs;
   - health metrics.
5. **The thin path:**
   - account, catalogue (seeded), subscription, the first invoice, the collection request;
   - the payment worker and the provider adapter;
   - the events back, invoice paid, access granted (switchable);
   - the checkout and status endpoints.
6. **Renewals:** the billing calendar (anchor day, clamping, local midnight, DST gaps) and the billing-schedule queue keyed by account and billing date.
7. **Provider isolation:**
   - the fake provider's failure modes;
   - a claim gate in the platform;
   - Resilience4j circuit breaker and per-instance rate limiter;
   - outcome classification;
   - reconciliation of `UNKNOWN` attempts, and of `FAILED` ones from the last 48 hours.
8. **Background safety:**
   - both spikes, run first;
   - event resubmission;
   - an optimistic lock on invoices;
   - invariant checks;
   - overdue signals.
9. **Chaos and load:** real-process tests, two defect fixes found by them, and a nightly workflow.

**Defects found by the tests and fixed** (each in its own commit, verified):

| Found by | Defect | Fix |
|---|---|---|
| `QueuePollingIT`, step 6 | A stopped poller could finish a poll cycle already running after `stop()` returned, and record a success. That would have hidden a stopped worker from AT-11. | `8bc3d63`: `stop()` waits for the poller thread. |
| Spike A finding 3, step 8 | Two concurrent deliveries of one `PaymentSucceeded` could both mark the invoice paid and publish `InvoicePaid` twice. | `c500d2b`: optimistic lock on `Invoice`. The test fails without it. |
| AT-9 first run, step 9 | Pollers left workers idle: about 4 renewals a second per instance (payment about 15), because a claim limited by free workers was treated as "queue drained". | `653383b`: claim up to the number of free workers, continuously, until the queue is drained. AT-9 went from "not finished after 9 min" to 56 s. |
| AT-10 first run, step 9 | 2.49% of calls refused with 429: each instance released its whole share once a second, in windows not aligned with the provider's. | `b7f27ad`: one permit every `1 s ÷ share`. 0% to 0.69% since. |

## 2. Spike findings

Full evidence, method and limits are in `docs/briefs/slice-1-spikes.md`. Both spikes are now permanent tests that assert the observed behaviour: `spikes.SpikeAEventPublicationIT` and `spikes.SpikeBReconciliationIT`.

### A. What Spring Modulith 2.1.1 really does (ADR-022)

**ADR-022's assumptions hold, so I did not stop.** Test methods `a1`, `a2`, `a2b`, `a3`, `a3b`:

| Question | Answer |
|---|---|
| 1. Crash between commit and listener | The publication stays `PUBLISHED` (listener not started) or `PROCESSING` (listener running), and incomplete. A restart without `republish-outstanding-events-on-restart` does nothing. With it, both are re-run at startup. `resubmitIncompletePublicationsOlderThan` recovers both. |
| 2. Listener throws | `FAILED`, incomplete. `IncompleteEventPublications` resubmits it once it is older than the threshold, measured on the injected clock. |
| 3. Two instances resubmit at once | Yes, the same publication can run twice: 9 and 12 of 20 in two runs. Running the job through the queue avoids this, because only one instance claims the job at a time. |

**Four findings refine ADR-022.** The resubmission job follows all four (`platform.internal.EventResubmission`):
1. `resubmitIncompletePublications(ResubmissionOptions)` resubmits only `FAILED` publications. A crash-left publication is never picked up by it. Only the older-than API covers crashes.
2. Uncoordinated resubmitters duplicate work, so the job must run through the queue.
3. A publication still running on another instance is resubmitted too, and runs twice at the same time. So the minimum age must stay well above any listener's run time, and listeners must be safe under concurrent duplicates.
4. Republish-on-restart re-runs work in flight on other instances, so it stays off.

### B. Does reconciliation find the truth? (ADR-018)

| Case | Result | Business time to resolution |
|---|---|---|
| B1 `TIMEOUT_AFTER_CHARGE`, charge succeeded | `UNKNOWN` → `SUCCEEDED`, invoice `PAID` | 5 min |
| B2 `TIMEOUT_AFTER_CHARGE`, charge declined | `UNKNOWN` → `FAILED (HARD)` | 5 min |
| B3 `LIE`, charged but answered "declined" | `FAILED` → `SUCCEEDED`, invoice `PAID` | 5 min |
| B4 `LIE`, declined but answered "succeeded" | **Never corrected**: invoice `PAID`, access granted, customer not charged | not resolved in 50 h |

Three directions resolve at the first check, well inside D3's 1-hour target. **B4 is undetected revenue leakage**, because reconciliation as specified never re-checks a `SUCCEEDED` attempt. ADR-018 does not cover this case. A decision is needed: section 4, R2.

## 3. Deviations from the brief or the decision records, and why

**Versions and libraries**
- **D1. Resilience4j 2.4.0 is pinned outside Spring Boot's dependency management** (ADR-026 says versions are pinned through Boot). Boot does not manage Resilience4j. `resilience4j-spring-boot4` 2.4.0 is built against Spring Boot 4.0.0, not the 4.1 line we use, so I used the core modules directly, as brief section 5 allows. The breaker runs on the injected clock, and I left the setting that starts its own scheduler thread off.
- **D2. ArchUnit has no explicit version.** It is 1.4.2, which comes in through `spring-modulith-starter-test`: the version Modulith 2.1.1 is built against, and one that supports Java 25. This keeps ADR-026's "pinned through Boot" as close as possible.

**Module boundaries and contracts**
- **D3. `catalog` may depend on `account`.** This was proposed in the step-1 plan and built without an explicit answer: `CatalogApi.quote(accountId, …)` needs the account's currency.
- **D4. The checkout endpoint (`POST /v1/subscriptions`) lives in `invoicing`, not `subscription`.** Checkout must record the token, subscribe, then wait on the invoice's payment. `invoicing` is the only module already allowed to use `account`, `subscription` and `payment`. Putting it in `subscription` would have changed that module's allowed dependencies, which needs your approval.
- **D5. Contract additions:**
  - `AccountApi.recordPaymentToken`. The token is stored on the account, so renewals can use it.
  - Five metrics beyond section 4:
    - `tally.queue.failed`: otherwise failed items would be invisible (D7);
    - `tally.job.waiting`: ADR-023 asks for each job's waiting work;
    - `tally.job.overdue` and `tally.queue.overdue`: one clear signal to alert on;
    - `tally.payment.breaker.state`.
  - `tally.payment.outcome_corrected` carries `from` and `to` tags.
- **D6. The billing day is not stored on `Account`** (brief section 1). ADR-002 makes the billing calendar an Invoicing concern. The anchor day lives in `invoicing.subscription_billing`, Invoicing's own record of the subscription, built from `SubscriptionChanged` (ADR-014's anticorruption layer).

**Payment behaviour**
- **D7. A 503, a refused connection or a connect timeout returns the attempt to `PENDING`**, like the brief's 429. The provider certainly did not charge, so this is not a retry of an unknown outcome (rule 9 still holds: a read timeout or anything else is `UNKNOWN`, never retried). Without it, AT-4's "all charged after recovery" cannot pass in slice 1.
- **D8. Reconciliation resolves an `UNKNOWN` attempt to `FAILED(SOFT)` when the provider has no charge recorded.** If the provider cannot be reached during reconciliation, the attempt stays `UNKNOWN` and the next check is backed off.
- **D9. A `PAYMENT_FAILED` invoice can become `PAID`** when reconciliation corrects the payment (needed for AT-6).
- **D10. The rate limiter hands out one permit every `1 s ÷ share`,** not the share once a second (AT-10's first run).

**Data and infrastructure**
- **D11. Tables outside the per-module rule:**
  - Spring Modulith's `event_publication` table is in `public`, next to Flyway's history; it belongs to no single module.
  - The platform module has its own schema, `platform`, holding only its job table (infrastructure, no business data).
  - Periodic jobs live in each owning module's own job table (`payment.jobs`, `invoicing.jobs`), as ADR-017 requires.
- **D12. Every queue table has the same shape,** checked at registration.
  - `FAILED` items stay failed until a person acts.
  - Finished queue rows and completed publications are never cleaned up in slice 1.
- **D13. Async event listeners run on Spring Boot's task executor,** which Spring Modulith enables itself. ADR-027 says the platform owns background threads. This pool is owned by the libraries, so the fitness functions cannot see it, and it is covered only by the `tally.events.*` metrics. See R3.

**Tests and CI**
- **D14. AT-9 and AT-10 live in `tally`'s integration tests, not in a `load-tests/` folder** (ADR-020). They reuse the Testcontainers database and fake-provider setup. AT-9 shortens the claim timeout (15 s) and the event resubmission age (20 s) so it finishes in about a minute; everything else is production settings.
- **D15. AT-10's "no 429 storm" is defined as at most 1% of calls refused.** The brief gives no figure. To confirm (Q1).
- **D16. CI builds one image per push, for the newest commit,** not one per commit: GitHub runs the workflow once per push.
- **D17. Test infrastructure:**
  - only one Spring test context is alive at a time (`spring.test.context.cache.maxSize=1`);
  - the test PostgreSQL allows 500 connections.

  Test contexts with pollers behave like instances whose clocks are years apart, sharing one database; kept alive together, they claimed each other's work.
- **D18. The build takes about 8 minutes.** Spike A (about 140 s, 11 application instances), AT-9 and AT-10 are most of it.
- **D19. The nightly workflow only runs on schedule from the default branch.** GitHub runs scheduled workflows on the default branch only, so it starts once this branch is merged. Until then it can be started by hand.

**Not built**
- **D20. ADR-024's "collected versus invoiced totals"** compares two modules' data, which rule 2 forbids one module from reading. Q4.
- **D21. Deployment to staging and production** is a stretch goal in the brief and was not done. AT-9's 1,000,000 accounts belongs to staging.

## 4. Open questions and suggested new decision records

**Suggested records.** I edited no accepted record; each of these would supersede or extend one.
- **R1. Event resubmission in practice** (extends ADR-022, from Spike A):
  - resubmit with the older-than API only;
  - run the job through the queue;
  - set the minimum age well above any listener's run time;
  - keep republish-on-restart off;
  - make listeners idempotent *under concurrency* (a constraint, an atomic insert or an optimistic lock, not "check, then act");
  - include the 15-minute alert threshold on `tally.events.oldest_incomplete_age_seconds`.
- **R2. Verify reported successes** (supersedes part of ADR-018). This closes Spike B's B4 gap. Either re-check `SUCCEEDED` attempts once, for example at 5 minutes, or compare against the provider's daily settlement report (D3's "daily totals check"). Then decide what happens to an invoice and to access when a mismatch is found. **Needs finance:** it changes what a customer sees after paying.
- **R3. Who owns event-listener threads** (clarifies ADR-027). Either accept Spring Boot's task executor for Modulith listeners and monitor it, or give it to the platform with its own health.
- **R4. Retrying calls that certainly did not charge** (extends ADR-018 for slice 1): 429, 503, a refused connection or a connect timeout go back to the queue (D7). Slice 2's idempotency keys will widen this.
- **R5. Where checkout lives** (D4). Either keep it in Invoicing, or create a process module with its own declared dependencies. The second needs your approval under CLAUDE.md.
- **R6. Rate limiting across instances.** The evenly spread limiter still lets about 0.7% of calls be refused at sustained full rate (10,000 renewals), because two instances each at exactly their share can put 101 or 102 calls into one provider second. Options: aim about 5% under the provider's limit, or act on the deferred "shared rate limiter" decision. That record's trigger is "more than two instances, or frequent 429s".

**Questions**
- **Q1.** Is "at most 1% of calls refused with 429" the right definition of "no 429 storm" (AT-10)?
- **Q2. Throughput against D6.**

  | Measure | Value |
  |---|---|
  | AT-10 at 10,000 renewals (two instances) | 87 charges a second |
  | Extrapolated to 500,000 | first attempts in about 1.6 h; D6 target 3 h |
  | AT-9, invoice creation, mostly one instance | about 100 invoices a second |
  | D6 needs | about 139 invoices a second (99.9% within 1 hour) |

  The invoice side may need more billing workers than the default 4, or more instances. **This is a scaled CI measurement, not D6's staging scenario.** CLAUDE.md asks me to stop when a response measure cannot be met. I have not shown that it cannot; I am flagging the risk for the staging load test.
- **Q3.** "UNKNOWN with no charge at the provider becomes FAILED(SOFT)" (D8): does finance agree? The invoice then shows `PAYMENT_FAILED`, with no retry in slice 1.
- **Q4.** "Collected versus invoiced totals" (ADR-024): run it as a finance export (slice 4), or have each module expose daily totals through its API?
- **Q5.** Retention: when may finished queue rows and completed event publications be deleted?
- **Q6.** Build time: should Spike A, AT-9 and AT-10 run nightly instead of on every push, if 8 minutes is too slow?

## 5. Fitness functions added

Each was shown to fail on a deliberate violation. The failing build output is in `docs/briefs/slice-1-fitness-proof.md`.

| ID | Rule | Test | Guards |
|---|---|---|---|
| FF-1 | Spring Modulith verification: no cycles, no access to internal packages, only declared dependencies (so `platform` depends on no business module) | `architecture.ModuleStructureTest.modulesRespectTheirDeclaredBoundaries` | ADR-001, ADR-003, ADR-014, ADR-027 |
| FF-2 | Exactly the agreed top-level modules | `architecture.ModuleStructureTest.topLevelPackagesAreExactlyTheAgreedModules` | CLAUDE.md rule 1 |
| FF-3 | No JPA relations between modules | `architecture.ArchitectureRulesTest.noJpaRelationsBetweenModules` | ADR-001, ADR-003 |
| FF-4 | No direct `now()`, `currentTimeMillis()` or system clock outside `ClockConfiguration` | `architecture.ArchitectureRulesTest.noDirectReadsOfTheSystemClock` | D4, ADR-003 |
| FF-5 | No `@Scheduled` or `@EnableScheduling` outside `platform` | `architecture.ArchitectureRulesTest.noSchedulingOutsidePlatform` | ADR-017, ADR-027 |
| FF-6 | No thread or executor creation outside `platform` | `architecture.ArchitectureRulesTest.noThreadOrExecutorCreationOutsidePlatform` | ADR-027 |
| FF-7 | Nothing in `payment` depends on `invoicing` | `architecture.ArchitectureRulesTest.paymentDoesNotDependOnInvoicing` | ADR-014 |
| FF-8 | `domain` packages do not depend on adapters or web | `architecture.ArchitectureRulesTest.domainDoesNotDependOnAdapters` | ADR-003 |
| FF-9 | Pinned event class list (renaming, moving or adding an event fails) | `architecture.PinnedEventClassesTest` (6 events pinned) | ADR-009 |
| FF-10 | Every `BackgroundJob` is a `HealthSignal` | `architecture.ArchitectureRulesTest.everyBackgroundJobReportsHealth` | ADR-023 |
| FF-10b | At runtime, every job and queue has its health metrics (`last_success_age_seconds`, `overdue`, queue gauges) | `platform.internal.EveryJobHasHealthMetricsIT` | ADR-023 |

**Permanent behaviour tests** that act as guards, though they are not structural rules:
- `spikes.SpikeAEventPublicationIT`: ADR-022's compliance test. It fails if a Spring Modulith upgrade changes the behaviour the resubmission job relies on.
- `spikes.SpikeBReconciliationIT`: pins reconciliation's behaviour, including the B4 gap. Fixing R2 must change that assertion deliberately.
- `invoicing.ConcurrentDeliveryIT`: shown failing without the optimistic lock.
- `platform.internal.DurableQueueIT`, including `twoInstancesClaimingConcurrentlyHandleEachItemExactlyOnce`: 1,000 items, two simulated instances.
- `platform.internal.PeriodicJobIT.twoInstancesRunADueJobOnce`.
