# Slice 1: spike findings

Both spikes from brief section 9 ran at the start of build step 8, before any step-8 code was written.
Versions: Spring Boot 4.1.1, Spring Modulith 2.1.1 (JDBC event publication registry, schema v2), PostgreSQL 17, Java 25.

Both spikes are now permanent tests that assert the behaviour observed below, as ADR-022's compliance section asks. A library upgrade that changes the behaviour fails the build.

| Spike | Test | Run time |
|---|---|---|
| A | `com.tally.spikes.SpikeAEventPublicationIT` (6 experiments) | about 140 s (11 application contexts) |
| B | `com.tally.spikes.SpikeBReconciliationIT` (4 scenarios) | about 40 s |

The evidence lines below are copied from the `SPIKE-A` and `SPIKE-B` lines these tests print. They come from one run of `./mvnw verify -Dit.test='Spike*IT'`. In the rows, `completed` is `completion_date IS NOT NULL`.

## Verdict on ADR-022

**ADR-022's assumptions hold.** Every point the record relies on was observed:

| ADR-022 assumes | Observed | Evidence |
|---|---|---|
| A failed listener leaves its publication incomplete | Yes: `status=FAILED`, `completion_date` empty | A2 |
| Outstanding publications are republished on restart only if a property enables it | Yes: without the property, nothing happens on restart; with it, everything outstanding is re-run | A1 |
| Otherwise the application must resubmit through `IncompleteEventPublications` | Yes: `resubmitIncompletePublicationsOlderThan` recovers both failed and crash-left publications | A1, A2 |
| A listener may run more than once | Yes, including **at the same time** on two instances | A3, A3b |

So I did not stop. Four findings refine the decision without contradicting it. Each is a design constraint for the resubmission job, and each is recorded in the slice report as a suggested amendment to ADR-022.

1. **Only one of the two resubmission APIs covers crashes.** `resubmitIncompletePublications(ResubmissionOptions)` is the newer API, and it resubmits `FAILED` publications only. A publication left `PUBLISHED` or `PROCESSING` by a crash is never picked up by it (A2b). Only the "older than" API (`resubmitIncompletePublicationsOlderThan`, or the predicate variant) covers both. *The job uses the "older than" API.*
2. **Two uncoordinated resubmitters run the same publication twice.** When two instances resubmit together, 9 and then 12 of 20 publications ran twice in two runs (A3). Modulith's "mark resubmitted" check does not stop this race. *The job runs through the durable queue, so only one instance resubmits at a time (ADR-017).*
3. **Resubmission does not know whether a publication is still running.** An incomplete publication older than the threshold is resubmitted even while another instance is still running its listener, so it runs twice concurrently (A3b). The queue cannot prevent this. *The job's minimum age must be well above any listener's run time. Listeners must be safe under concurrent duplicates, not only repeated ones: a database constraint, an atomic insert or an optimistic lock, not "check, then act".*
4. **`republish-outstanding-events-on-restart=true` re-runs publications another instance is still processing** (A1, with the property). With two instances in a rolling deployment, every restart would duplicate work in flight. *Keep it off (the default); the job covers the restart case.*

Also observed:
- **The registry measures publication age with the injected `Clock`.** `resubmitIncompletePublicationsOlderThan(1 min)` did nothing until the test clock moved (A2), which is consistent with D4.
- **Modulith 2.1 has a staleness monitor that marks old publications `FAILED`.** It needs staleness settings and Spring scheduling, and neither is enabled. It is off, and these results do not depend on it.

## Spike A: what does Spring Modulith really do?

**Method.** A test-only listener in package `spike.modulith`, outside `com.tally`, does what each experiment tells it per event: succeed, throw, or block without responding to interrupts, as a listener stuck in I/O would. Each experiment starts real Tally application contexts by hand on the shared test database. A "crash" is instance A shutting down while its listener is still blocked. A "restart" is a new instance B on the same database. Instance A's rows did not change when it shut down (A1, "after instance A stopped"), so the state B finds is the state a crash leaves.

### Question 1: a crash between commit and listener execution

Instance A has one async thread. The first listener (`during`) blocks while running. The second (`before`) is queued behind it and never starts.

```
A1-no-republish | committed; listener running (during) / not started (before) | invocations=1 | rows=[{status=PROCESSING, completed=false, completion_attempts=1}]
A1-no-republish | committed; listener running (during) / not started (before) | invocations=0 | rows=[{status=PUBLISHED, completed=false, completion_attempts=1}]
A1-no-republish | after instance A stopped | invocations=1 | rows=[{status=PROCESSING, completed=false, completion_attempts=1}]
```

**State after the crash:** `PROCESSING` if the listener had started, `PUBLISHED` if it had not. In both cases `completion_date` is empty.

**Restart without `republish-outstanding-events-on-restart`:** nothing happens. Then `resubmitIncompletePublicationsOlderThan(1 min)`, with the clock moved 5 minutes, recovers both.

```
A1-no-republish | instance B restarted (republish-on-restart=false), 3 s later | invocations=1 | rows=[{status=PROCESSING, completed=false, completion_attempts=1}]
A1-no-republish | instance B restarted (republish-on-restart=false), 3 s later | invocations=0 | rows=[{status=PUBLISHED, completed=false, completion_attempts=1}]
A1-no-republish | after resubmitIncompletePublicationsOlderThan(1 min), clock +5 min | invocations=2 | rows=[{status=COMPLETED, completed=true, completion_attempts=2}]
A1-no-republish | after resubmitIncompletePublicationsOlderThan(1 min), clock +5 min | invocations=1 | rows=[{status=COMPLETED, completed=true, completion_attempts=2}]
```

**Restart with the property:** both are re-run at startup, including the one whose listener may still be running elsewhere (finding 4).

```
A1-republish | instance B restarted (republish-on-restart=true), 3 s later | invocations=2 | rows=[{status=COMPLETED, completed=true, completion_attempts=2}]
A1-republish | instance B restarted (republish-on-restart=true), 3 s later | invocations=1 | rows=[{status=COMPLETED, completed=true, completion_attempts=2}]
```

**The other API does not cover crashes (A2b).** After a crash, `resubmitIncompletePublications(ResubmissionOptions...)` resubmits the failed publication but not the crash-left one (finding 1):

```
A2b | before: listener threw | invocations=1 | rows=[{status=FAILED, completed=false, completion_attempts=1}]
A2b | before: crashed while running | invocations=1 | rows=[{status=PROCESSING, completed=false, completion_attempts=1}]
A2b | after resubmitIncompletePublications(ResubmissionOptions, minAge 1 min), clock +5 min | invocations=2 | rows=[{status=COMPLETED, completed=true, completion_attempts=2}]
A2b | after resubmitIncompletePublications(ResubmissionOptions, minAge 1 min), clock +5 min | invocations=1 | rows=[{status=PROCESSING, completed=false, completion_attempts=1}]
```

### Question 2: a listener throws

```
A2 | listener threw | invocations=1 | rows=[{status=FAILED, completed=false, completion_attempts=1}]
A2 | resubmitIncompletePublicationsOlderThan(1 min), clock not moved | invocations=1 | rows=[{status=FAILED, completed=false, completion_attempts=1}]
A2 | resubmitIncompletePublicationsOlderThan(1 min), clock +5 min | invocations=2 | rows=[{status=COMPLETED, completed=true, completion_attempts=2}]
```

**State:** `FAILED`, incomplete. `IncompleteEventPublications` resubmits it, but only once it is older than the threshold on the injected clock. On success it becomes `COMPLETED`, and `completion_attempts` counts the deliveries.

### Question 3: two instances resubmit at the same time

Without coordination, yes, the same publication can run twice. Both instances resubmitted the same 20 failed publications at the same moment:

```
A3 | two instances resubmit 20 failed publications together | publications re-run more than once: 12 of 20 | invocations per publication: [2, 2, 2, 2, 2, 3, 3, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3]
```

The previous run of the same experiment re-ran 9 of 20; the exact number depends on timing. Each publication ran once and failed, then was resubmitted, so 2 is correct and 3 is a duplicate. The brief's premise holds: running the resubmission job through the queue means only one instance claims it per run. `PeriodicJobIT.twoInstancesRunADueJobOnce` shows that guarantee.

The queue cannot help when one instance resubmits a publication that another instance is still running (A3b, finding 3):

```
A3b | instance A's listener running | invocations=1 | rows=[{status=PROCESSING, completed=false, completion_attempts=1}]
A3b | B resubmitted while A's listener still runs | invocations=2 | max concurrent runs of one publication=2 (was 1)
```

### Limits of the method

- The crash was a shutdown with the listener still blocked, not `kill -9`. The rows did not change during shutdown, so the state is the same, but a hard kill was not tested.
- Each experiment ran once per build; the A3 count varies between runs.

## Spike B: does reconciliation find the truth?

**Method.** The full path: subscribe, then invoice, payment, the fake provider, and reconciliation. The provider is set to each mode, with the true outcome both ways. The test then moves the clock a minute at a time, waiting each step for the reconciliation job to run, until the attempt changes or 50 hours pass. Time to resolution is business time. Reconciliation is configured as in production: every minute, first check after 5 minutes, then 1 hour, then daily.

```
B1 TIMEOUT_AFTER_CHARGE | provider truth: SUCCEEDED | provider answered: (no answer: read timeout) | Tally believed: UNKNOWN | after reconciliation: SUCCEEDED | business time to resolution: 5 min | provider charges: 1 | invoice: PAID
B2 TIMEOUT_AFTER_CHARGE + DECLINE_HARD(100) | provider truth: DECLINED_HARD | provider answered: (no answer: read timeout) | Tally believed: UNKNOWN | after reconciliation: FAILED (HARD) | business time to resolution: 5 min | provider charges: 1 | invoice: PAYMENT_FAILED
B3 LIE | provider truth: SUCCEEDED | provider answered: DECLINED_HARD | Tally believed: FAILED | after reconciliation: SUCCEEDED | business time to resolution: 5 min | provider charges: 1 | invoice: PAID
B4 LIE + DECLINE_HARD(100) | provider truth: DECLINED_HARD | provider answered: SUCCEEDED | Tally believed: SUCCEEDED | after reconciliation: SUCCEEDED | business time to resolution: NOT RESOLVED within 50 h | provider charges: 1 | invoice: PAID
```

| Scenario | Truth / answer | Resolved? | Time |
|---|---|---|---|
| B1 timeout after a successful charge | SUCCEEDED / none | Yes, to SUCCEEDED, invoice PAID | 5 min |
| B2 timeout after a declined charge | DECLINED_HARD / none | Yes, to FAILED (HARD) | 5 min |
| B3 success reported as a decline | SUCCEEDED / DECLINED_HARD | Yes, to SUCCEEDED, invoice PAID | 5 min |
| B4 decline reported as a success | DECLINED_HARD / SUCCEEDED | **No.** The invoice stays PAID, and access stays granted, for a customer who was never charged | never (checked for 50 h) |

**Finding.** In three of four directions reconciliation finds the truth at its first check, 5 minutes after the outcome. That is well inside D3's "UNKNOWN resolved within 1 hour" (assumed) and "none after 24 hours". Time to resolution is the 5-minute first check plus at most one job interval of 1 minute. If the provider cannot be reached when reconciliation reads, the next check is 1 hour later, then daily, so an outage of more than an hour pushes resolution past D3's target.

**B4 is revenue leakage that nothing detects** (business goal 1: no revenue leakage). ADR-018 and the brief reconcile only `UNKNOWN` attempts and `FAILED` attempts from the last 48 hours, so a `SUCCEEDED` attempt is never checked again. The record does not conflict with this behaviour; it simply does not cover it. **Suggested new decision record:** either re-check `SUCCEEDED` attempts too, for example once at 5 minutes, or rely on D3's "daily totals check" against the provider's settlement report and decide what happens to access when a mismatch is found. This needs finance, because it changes what a customer sees after paying. I did not widen step 8 to cover it.
