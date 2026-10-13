# Test-isolation rollout: the 10-run evidence

The rollout of option B2 from the Slice 2 step 0 spike (`docs/briefs/slice-2-spike-test-isolation.md`): a database per test class and per set of hand-started instances, every patch removed, fitness function F13.

**Command per run:** `run-suite.sh <label> 10 <checkout>`. This is the spike's harness, pointed at this folder. It runs the integration and acceptance tests (`./mvnw -o -B -pl tally verify -Dtest=none`), one run at a time, on one machine (4 CPUs, 15 GB).

## Run sets

| Set | Commit | Runs | Failures | Median | Contexts |
|---|---|---|---|---|---|
| `results/rollout` | 106e85f | 10 | 0, 0, 0, 0, 0, 1, 1, 1, 0, 0 | 425 s | 55 |
| `results/rollout-2` | this PR's head | 10 | 0 ×10 | 422.5 s | 55 |

## The first set's failures, and the fix

**What failed:** `ReconciliationIT.aGenuineDeclineIsRecheckedWithBackoffForFortyEightHoursOnly`, in runs 6, 7 and 8. It expected 3 checks and found 4.

**Why:** the test calls reconciliation directly. With the 9999 parking removed, its context's own scheduled reconciliation sometimes ran a check between two of those calls. The spike had inferred this hazard (H3) without being able to show it: 0 failures in 7 runs without the parking. Here it showed in 3 of 10.

**The fix:** `ReconciliationIT`'s context schedules reconciliation every ten years (`tally.payment.reconciliation.interval=3650d`). The job runs once as the context starts, which the base class waits for, and never again within the days these tests move the clock. It is a setting, not a rewrite of the job's row.

## Contexts

There are 55 contexts per run: one per test class with a Spring context. The spike's B2 had 56; `SharedJobIT` is gone. The configurations column counts cache keys as text; it does not show the test class.
