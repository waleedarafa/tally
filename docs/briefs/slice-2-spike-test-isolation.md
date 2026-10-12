# Slice 2, step 0: spike on test isolation

The brief is posted verbatim on PR #37. Everything below was measured on one machine (4 CPUs, 15 GB, Docker), between 06:30 and 17:10 UTC on 12 October 2026, against `main` at `c530e9c`. The review of 12 October (posted verbatim on #37) added option B2 and the re-measurement in this version.

## The question

How should the test suite be isolated, so that a test that waits on background work cannot be disturbed by another test context, without a per-test workaround?

## Summary

- **Recommendation: B2, one database per test class.** Each one is copied from a template database migrated once per run. So is the database of every set of instances a test starts by hand.
  - **No option beat the baseline with the patches on.** D, B and E were each green in 10 of 10 runs, and B2 in 6 of 6. The CI flakes did not reproduce in 10 baseline runs on this machine. Wall time showed no measurable difference.
  - **B2's case is the patches it removes.** With every patch switched off, the baseline fails 12 tests per full run and 10 per run in the hostile order. B2, with the AT-12 change below, fails only `SharedJobIT` in the full suite (3 of 3 runs) and nothing in the hostile order (3 of 3). `SharedJobIT` exercises the re-arm itself, and B2 makes its scenario impossible.
  - **B2 needed one test change.** AT-12 waits for its own job's first run, then moves its clock to the job's next run, instead of re-arming the row (section 4).
- **The cost is realism.** A test class no longer meets rows other tests left behind. Production's competition between instances is kept only where a test builds it on purpose, as AT-9, AT-10, `PeriodicJobIT` and `DuplicateCollectionRequestIT` already do.
- **The brief's premise needs one correction.** Cached Spring test contexts never ran their job runners at the same time. Spring Framework 7.0.9 closes the old context before it loads the next one, and the cache holds one context (section 1). The interference came from:
  - **Rows left behind.** A context leaves rows rescheduled on its own clock, and a later context finds them on a different clock (the stranded job of AT-15).
  - **A context's own runners.** Its own scheduled jobs race the test's direct calls. This is an inference for `InconsistentLookupIT` and `ReconciliationIT` (section 3).
  - **Hand-started instances.** Each runs on its own clock, on the container's shared database.
- **B, one database per context, isolates contexts but not test classes.** Under B, 56 test classes with a Spring context ran in 47 context starts, so 9 times a class reused the previous class's context and database. B2 gives every class its own.
- **A, one shared clock, made the suite worse.** Every row an earlier test left behind becomes due work in every later context: 3 of 3 runs failed (11 failures).
- **E, B with cached contexts, found a production defect.** `QueuePoller.stop()` can lose a claimed item and a worker permit. With Spring's default pausing of cached contexts, a restarted context's job queues stopped claiming. The fix goes in its own PR (section 5).
- **C, runners off, was only sampled.** It needs 31 test classes rewritten, and its end-to-end context would need A's clock discipline.

## 1. What actually shares what (evidence)

| Claim in the brief | What the spike found | Evidence |
|---|---|---|
| "Several cached Spring test contexts … each runs its own job runners" at the same time | Only one cached context is alive at a time. `spring.properties` sets `spring.test.context.cache.maxSize=1` (since Slice 1 step 7, 7 October). spring-test 7.0.9 evicts the least recently used context *before* it loads the next (`DefaultContextCache.put(key, LoadFunction)`: `evictLruContextIfNecessary`, then `pauseOnContextSwitchIfNecessary`, then `loadContext`; read from the jar's bytecode, as the documentation host is blocked here). | Baseline log, every context switch: the old context's `Closing JPA EntityManagerFactory` comes before the new context's `Started …IT`. All four flakes happened with this setting in place. |
| A context whose clock is far ahead reschedules a shared row into a future the others never reach | **Confirmed, but in sequence, not at the same time.** AT-3's context ends in April 2028, so the job rows it runs are rescheduled into 2028. Every later context starts at 2026-01-15 and never sees them due. (The 2028 dates are inferred from AT-3's clock; the failures are the evidence.) | Patches off, hostile order (AT-3 first): 10 failures per run in 3 of 3 runs (`D-hostile`) |
| Another context's job resolved the attempt first (`InconsistentLookupIT`, #20; `ReconciliationIT`, #23) | No other context was alive. **Inference, not shown:** the runner that interfered was the test's own context's scheduled reconciliation (`AbstractReconcilingTest` turns polling on), racing the test's direct calls. | B, patches off: `ReconciliationIT` failed in 1 of 5 full runs. B2, parking off: 0 failures in 7 full runs, so the cause could not be isolated (section 3). |
| AT-12 (#36): another context ran the invariant job on its own clock | **Wrong, as I explained it in #36.** No other test context was alive; the CI run's hand-started instances (AT-9, AT-10) use their own databases. The real cause of the 0.0 is still not established. One thing the CI log does show: AT-12's context, with polling on, was also reconciling other tests' leftover attempts against an unreachable provider. #36's change stays correct and harmless. | CI log of run 38160641754, attempt 1: AT-12 runs after AT-15 and AT-9 and fails in 0.24 s |
| — | Hand-started instances (`TallyInstances`: AT-7, Spike A, Spike S-1, `PricingRulesStartupCheckIT`) run beside the test context, each on its own `TestClock`. On D, A, B and E they use the container's shared database; on B2, a database of their own. | 25 such instances per run, from 15 `TallyInstances` objects |

**So the residual hazards are:**
- (H1) rows left behind under another clock;
- (H2) leftover rows of other tests, picked up as live work;
- (H3) a context's own runners racing its test's direct calls;
- (H4) per-context state (a gauge) that only this context's runner sets.

## 2. The options and their six measures

**How each measure was taken:**
- **Contexts:** the context cache's last `missCount` in the debug log.
- **Configurations:** the distinct cache keys in the trace log. Under B2 the key's text does not show the test class, so it still reads 30; the cache treats each class as its own key.
- **Flake rate:** every failure was recorded with its test name (`results/*/failures.txt`).
- **Duration:** wall time of `./mvnw -o -pl tally verify` with the integration and acceptance tests only.
- **Hostile:** AT-3 first, then the nine tests that wait on shared job rows, with the patches switched off.

| | **D. Status quo** (baseline) | **A. One forward-only test clock** | **B. Database per context** | **B2. Database per test class** | **C. Runners off, drive directly** | **E. B + cache 32, no pausing** |
|---|---|---|---|---|---|---|
| **1. Contexts created** | 47 | 47 | 47, plus 47 databases | **56**: one per test class with a Spring context (8 of the 64 IT classes have none), plus 56 context databases and 15 instance databases | Not measured for the suite. The sample moves AT-12 into the default configuration. | 30 |
| **2. Flake rate, patches on** | 0 failures in 10 runs | **Failures in 3 of 3 runs**: 6, 2, 3 (AT-4 ×3, AT-11 ×3, `UnknownResolutionIT` ×2, `EventSequenceIT` 3 tests ×1). Stopped at 3 runs: two of the failures are deterministic. | 0 failures in 10 runs | 0 failures in 6 runs (3 before and 3 after AT-12's change) | Sample only: converted AT-12, 0 failures in 5 runs, after AT-3, patches off | 0 failures in 10 runs (with Spring's default pausing: 5 failures in 1 run, section 5) |
| **2b. Patches off: full suite / hostile order** | 12, 12 / 10, 10, 10 | 8 / 1 | 3, 1 / 1, 1, 1. With AT-12 advancing its clock: 1, 1, 1 (`SharedJobIT` only) / 0, 0, 0 | 2, 1, 1 (`SharedJobIT` ×3, AT-12 ×1) / 0, 0, 0. **With AT-12 waiting for its job's first run: 1, 1, 1 (`SharedJobIT` only) / 0, 0, 0** | Sample: 0 in 5 after AT-3 | 6, 5 / 1, 2, 0 |
| **3. Suite duration, median wall time** | 427 s (413 to 439) | 431 s (3 failing runs) | 415.5 s (411 to 427) | 422 s (412 to 443, 6 runs) | Not measured | 428.5 s (421 to 441) |
| **4. Blast radius** | — | 2 infrastructure classes and 2 tests rewritten (AT-3, `FirstInvoiceIT`). 4 more fail and would need rewriting or data clean-up (`EventSequenceIT`, AT-4, AT-11, `UnknownResolutionIT`). | 3 infrastructure files (`AbstractIntegrationTest`, new `PerContextDatabase`, `spring.factories`); no test class | B's 3 files, plus `TallyInstances`, `PricingRulesStartupCheckIT` (reads its instances' database) and AT-12 | 31 test classes (list below), plus a test driver | As B, plus `spring.properties` |
| **5. What it removes** | — | Nothing shown. Stranding cannot happen by construction, but with all patches off 8 tests failed: the waiting tests need clock advances, and others fail on leftovers. | The cross-context patches; `ReconciliationIT` failed once in 5 runs without its parking | **Every patch** (section 3); only `SharedJobIT`, now obsolete, fails without them | All job-row patches in converted tests | As B; the cached reconciliation context needs the clock advance in AT-5, AT-6 and AT-15 (4 tests) |
| **6. Realism** | Loses: one wall clock (contexts years apart). Keeps: shared database, incidental competition. | Keeps: one clock, one database, all instances competing for every row, the closest to production. Loses: the test's control over what work exists. | Keeps: one clock per database; competition where a test builds it. Loses: incidental competition with other tests' leftovers, which no test asserts on. | As B, for test classes and for hand-started instances. Every test class now starts while its context's periodic jobs make their first run, as a freshly started instance does. | Loses: the scheduler end to end, for converted tests. Keeps it in one dedicated context. | As B; idle cached contexts keep polling their own database |

Where a test builds the competition on purpose: AT-9, AT-10, `PeriodicJobIT.twoInstancesRunADueJobOnce`, `DuplicateCollectionRequestIT`, `ConcurrentRenewalIT`, `ConcurrentDeliveryIT`.

**Wall time:** there is no measurable difference between D, B, B2 and E. D ran first, and its own times fell from 438 s to 413 s over its 10 runs (a warming machine). So the differences between the medians, up to 12 s, are not evidence either way.

**Startup cost of a database per class:**
- one template migration per run, 1.1 to 1.5 s;
- each copy (`CREATE DATABASE … TEMPLATE`): median 55 ms, p95 131 ms, over 1,054 copies in the B2 runs;
- 71 copies per full run (56 contexts, 15 sets of instances), about 4 s per run.

The first copy of each run also times the template migration.

**E's cache** saved 17 of 47 context starts, but no wall time. Contexts start in about a second, and idle cached contexts keep polling.

### A in detail: what one clock does to leftovers

With one clock that only moves forward, every row an earlier test left behind becomes due in every later context:
- **`EventSequenceIT`:** collection items left by AT-11 (`UNAVAILABLE`, retried as new attempts: 35 technical failures in run 1) were being worked when it started. Its own payments never completed within 15 s. That they were held up by the leftovers is my reading of the log, not traced further.
- **AT-4:** it counted 0 technical failures among its own references. My reading, not traced further: in the outage month, other tests' leftover items opened the breaker before AT-4's own renewals were tried.
- **`UnknownResolutionIT`:** counted a leftover attempt (2, expected 1, in runs 1 and 3).
- **AT-11:** jobs whose rows are old on the shared timeline show as overdue at once (1.0, expected 0.0).

A also needs every absolute date rewritten. AT-3 now computes:
- Santiago's next midnight gap from `ZoneRules`;
- New York's month-ends;
- the next leap February.

Its span now depends on where the clock stands: 22 New York invoices in A's run 1 and 24 in the hostile run, against 27 on `main`. By the code, it can reach about 50 when the next leap year is four years away. `FirstInvoiceIT` now moves to the next 10:00 UTC.

## 3. Today's patches, and which option makes each one unnecessary

Each was switched off with `-Dtally.spike.patches=off` (`prototypes/0-patches-switch.patch`); on by default. For B2 the switch also covers AT-7's and Spike S-1's clean-up deletes. It can keep `ReconciliationIT`'s parking or the reconciling reset on alone (`-Dtally.spike.patches.keep=…`).

| Patch | Guards against | D | A | B | B2 | C |
|---|---|---|---|---|---|---|
| `SharedJob` re-arm (AT-5, AT-6, AT-8, AT-12, AT-15, `EventSequenceIT`, `EventResubmissionIT`, Spike B) | H1, and "the job is not due yet on this clock" | Needed. With all patches off: 12 failures | Not shown. With all patches off: 8 failures (AT-4, AT-6, AT-11, AT-12, AT-15 ×2, `ReconciliationIT`, `SharedJobIT`) | H1 gone. "Not due yet" remains: AT-12 advances its clock (3 of 3) | **Not needed** once AT-12 waits for its job's first run and then advances its clock (0 failures besides `SharedJobIT` in 6 runs) | Gone for converted tests (shown for AT-12 only) |
| `@BeforeEach` row resets (`AbstractReconcilingTest`, AT-7, AT-8, AT-12, `EventResubmissionIT`) | H1 | Needed | Not measured separately | Not needed: none failed in 8 patches-off runs | **Not needed**: none failed in 14 patches-off runs (8 full, 6 hostile order); the reconciling reset was on in one of the full runs | Gone |
| 9999 parking and its `@AfterEach` restore (`ReconciliationIT`) | H3, by inference: its own context's scheduled job | Needed | Needed | Failed in 1 of 5 full runs without it | **Not needed in what was measured**: 0 failures in 7 full runs without it, 1 of them with only the reconciling reset on. With 0 failures either way, the parking and the reset cannot be told apart, so the H3 attribution stays an inference. | Gone: the test drives reconciliation itself |
| AT-7's and Spike S-1's deletes of unfinished publications | Leftover publications from other tests | Not measured | Not measured | Not measured | **Not needed**: each test's instances get a fresh database; 0 failures in 8 full patches-off runs | — |
| AT-12 waiting on its own gauge (#36) | H4 | Harmless | Not measured | Harmless | Harmless; no other context or instance writes its rows | Gone: the test runs the check itself |
| `SharedJobIT`, the stranding reproducer | — | Needed as a test | — | Obsolete | **Obsolete**: the only failure left in the full suite with every patch off (8 of 8 full runs) | Obsolete |

## 4. Recommendation and the rollout PR

**Recommendation:** B2. No test class meets a row it did not create.

**The trade-off:**
- **Gained:** a test can no longer be disturbed by a row it did not create, under any clock, and no test has to know that. All the patches go.
- **Lost:** the suite no longer stumbles on other tests' leftovers. That was accidental realism, and no test asserted on it.
- **Kept, where it matters:** production's competition between instances on one database (ADR-003) stays tested where it is the point of the test (section 2).
- **New, and production-like:** every test class starts while its periodic jobs make their first run. A test that moves time must first let that run end; otherwise the job's next run is set from the moved clock (AT-12, run 1 of `B2-patches-off`).

**What the rollout PR would do:**
1. **Add the infrastructure:**
   - `support/PerContextDatabase`: a `ContextCustomizerFactory` in `META-INF/spring.factories`, with the test class in its customizer's equality, plus `newDatabase` for hand-started instances;
   - remove `@ServiceConnection` from `AbstractIntegrationTest`;
   - `TallyInstances` copies one database per object;
   - `PricingRulesStartupCheckIT` writes to its instances' database.
   - The cache size no longer matters: no two test classes share a context.
2. **Make the job due honestly.** A test that waits on a periodic job waits for the job's first run in its own context, then moves its clock to the job's next run. It does not rewrite the row. Only AT-12 needed this in the measured runs.
3. **Remove every patch:**
   - `SharedJob` and `SharedJobIT`;
   - the `@BeforeEach` row resets in `AbstractReconcilingTest`, AT-7, AT-8, AT-12 and `EventResubmissionIT`;
   - `ReconciliationIT`'s 9999 parking and its restore;
   - AT-7's and Spike S-1's clean-up deletes.
   - AT-12's own-gauge wait can stay as written.
4. **Watch `ReconciliationIT`.** If it fails again, the next step is to run it, `UnknownResolutionIT` and `RequestedAttemptIT` with runners off, because they call reconciliation directly. That is C's idea where it pays. It is not part of the rollout, because nothing measured needs it.
5. **Update the Slice 1R report's §7** to the mechanism in section 1.
6. **Run the suite 10 times before merging**, as here.

**What was measured:** B2 with AT-12's change and every patch off failed only `SharedJobIT`, in 3 of 3 full runs (`results/B2-AT12-waits-patches-off`, median 419 s). The hostile order was green in 3 of 3 (`results/B2-AT12-waits-hostile`).

### Which prototypes should merge

- **None of the prototype code should merge from this PR.** This PR carries the report, the harness, the result files and the prototypes as patches (`prototypes/`). The rollout PR rebuilds B2 cleanly, without the patch switch.
- `C-sample-drive-directly.patch`'s `Drive` helper is the starting point if step 4 is ever needed.

### Open questions

1. **AT-12's CI failure** of 11 October is still unexplained. B2 removes every explanation I could find. If it recurs under B2, it is not isolation.

## 5. A production defect found by E (fixed in its own PR)

`QueuePoller.stop()` runs these steps in order:
1. `scheduler.shutdownNow()`;
2. `workers.shutdown()`;
3. waits for the scheduler;
4. waits for the workers.

A poll cycle still running after step 2 claims rows and hands them to the terminated worker pool. `workers.execute` throws `RejectedExecutionException`, which is caught as "poll failed". Two things follow:
- the claimed row stays `IN_PROCESSING` until the reaper returns it;
- the worker permit taken for it is never released.

A job queue has one worker, so a poller that is started again claims nothing, ever.

It happened in the E check run (`results/E-paused-check`). Spring paused the reconciliation context (`spring.test.context.cache.pause` defaults to `ON_CONTEXT_SWITCH` in spring-test 7.0.9) as `CollectAgainIT` started; see the log line `Queue jobs-payment-jobs: poll failed … RejectedExecutionException`. Every later test on that context timed out waiting for reconciliation.

**Production impact:** low but real.
- A shutdown can leave a claimed item to the reaper. That is already tolerated (rule 5).
- Any stop and start of a poller in one process (`QueueRuntime.stop`/`start`, a `Lifecycle` stop and start) can leave a single-worker queue dead.
- The method's own comment says "so nothing is claimed or recorded once stop returns".

**Proposed fix:**
- wait for the scheduler to terminate before shutting the workers down;
- release the permit when a hand-off is rejected.

This is a production change, so it is not made here. It goes in its own small PR: the failing test first (stop the poller while a claim is in flight, start it again, assert it claims a due item), then this fix.

## 6. Evidence and commands

**Harness:** `slice-2-spike-test-isolation/run-suite.sh <label> <runs> <checkout>`, one checkout (git worktree) per option. Runs were one at a time, never in parallel.

**Command per run:**

```
./mvnw -o -B -pl tally verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
  -Dlogging.level.org.springframework.test.context.cache=DEBUG \
  -Dlogging.level.org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate=TRACE $EXTRA
```

- **Patches off:** `EXTRA=-Dtally.spike.patches=off`.
- **Hostile order:** additionally `-Dit.test=AT03CalendarEdgesIT,AT05TimeoutAfterChargeIT,AT06LyingProviderIT,AT08ListenerFailsIT,AT12InvariantCheckIT,AT15CrashAfterAttemptSentIT,EventResubmissionIT,ReconciliationIT,SpikeBReconciliationIT,UnknownResolutionIT -Dfailsafe.runOrder=alphabetical`.

**Results:** `slice-2-spike-test-isolation/results/<label>/`:
- `runs.csv`: run, seconds, failures, contexts, configurations, instances;
- `failures.txt`: run, test, kind, first line;
- `keys-N.txt`: the distinct context configurations.

Raw logs stayed local (about 1 MB each).

| Label | Option | Runs | Failures per run | Median s |
|---|---|---|---|---|
| D | baseline, `main` | 10 | 0 ×10 | 427 |
| B | database per context | 10 | 0 ×10 | 415.5 |
| E | B, cache 32, pausing off | 10 | 0 ×10 | 428.5 |
| A | shared clock | 3 | 6, 2, 3 | 431 |
| E-paused-check | B, cache 32, Spring's default pausing | 1 | 5 | 469 |
| D-patches-off / B / E / A | patches off, full suite | 2 / 2 / 2 / 1 | 12, 12 / 3, 1 / 6, 5 / 8 | 558.5 / 438 / 491 / 481 |
| D-hostile / B / E / A | patches off, AT-3 first | 3 / 3 / 3 / 1 | 10 ×3 / 1 ×3 / 1, 2, 0 / 1 | 212 / 107 / 106 / 106 |
| B-hostile-AT12-advances | B; AT-12 advances its clock; patches off, AT-3 first | 3 | 0 ×3 | 95 |
| B-patches-off-AT12-advances | B; AT-12 advances its clock; patches off, full suite | 3 | 1 ×3, `SharedJobIT` only | 424 |
| B2 | database per test class and per set of instances; AT-12 advances its clock | 3 | 0 ×3 | 422 |
| B2-patches-off / B2-hostile | the same, patches off: full suite / AT-3 first | 3 / 3 | 2, 1, 1 / 0 ×3 | 433 / 100 |
| B2-patches-off-keep-parking / -keep-reset | patches off except `ReconciliationIT`'s parking / except the reconciling reset | 1 / 1 | 1 / 1 (`SharedJobIT` only) | 434 / 432 |
| B2-AT12-waits | B2; AT-12 also waits for its job's first run | 3 | 0 ×3 | 422 |
| B2-AT12-waits-patches-off / -hostile | the same, patches off: full suite / AT-3 first | 3 / 3 | 1 ×3 (`SharedJobIT` only) / 0 ×3 | 419 / 98 |
| C-sample-after-AT03 | C sample: AT-3, then the converted AT-12 | 5 | 0 ×5 | 38 |
| C-sample-hostile | first attempt; the sample did not compile (`PeriodicJob.run` throws `Exception`) | 5 | build failed | — |
| A-check / B-check | first full run of each prototype after two fixes: A's shared clock had been initialised before `DEFAULT_START`; B's `@ContextConfiguration` hid nested test configurations. The broken first runs were discarded. | 1 / 1 | 6 / 0 | 475 / 415 |

### The runner-on test classes C would rewrite (31)

All 31 run with `tally.platform.polling-enabled=true`, directly or through `AbstractReconcilingTest`. C would turn runners off in each and drive the work. The 9 acceptance tests in **bold** are the end-to-end candidates for the one dedicated context, which then needs A's forward-only clock and relative dates:
- **acceptance:** **AT-1**, **AT-2**, **AT-3**, **AT-4**, **AT-5**, **AT-6**, AT-7, AT-8, AT-12, **AT-14**, **AT-15**, **AT-16**;
- **platform:** AT-11, `EventResubmissionIT`, `EveryJobHasHealthMetricsIT`, `QueuePollingIT`;
- **invoicing:** `DuplicateCollectionRequestIT`, `FirstInvoiceIT`, `ZeroInvoiceIT`, `PaymentDeclinedIT`;
- **payment:** `CollectAgainIT`, `PaymentCollectionIT`, `ProviderIsolationIT`, `ReconciliationIT`, `RequestedAttemptIT`, `UnknownResolutionIT`;
- **subscription:** `OfferVersionPinnedIT`, `RenewalIT`;
- **other:** `EventSequenceIT`, Spike B, `SharedJobIT`.

C was not prototyped beyond AT-12. It needs a `Drive` helper in the test sources of `platform.internal`: `DurableQueue.processDue()` is public, and the invariant jobs are reached by package. I stopped at the time box. The suite-wide C measures (contexts, flake rate, duration) are therefore not measured.

## 7. Proposed decision record

Added to `docs/architecture/v2/README.md`, "Proposed", for confirmation:

> **029 Test isolation.** No test class meets a row it did not create. Every test class, and every set of instances a test starts by hand, gets its own PostgreSQL database copied from a template migrated once per run (`CREATE DATABASE ... TEMPLATE`). A test that needs instances competing for rows starts them on one database it owns (AT-9, AT-10, `PeriodicJobIT`). A test that waits on a periodic job moves its clock to the job's next run; no test rewrites a job row to make it due. *From: Slice 2 step 0 spike (this report).*
