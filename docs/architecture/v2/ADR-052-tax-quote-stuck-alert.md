# ADR-052. An alert for a tax-quote queue that keeps retrying

**Status:** Accepted, 26 November 2026 (WAR-67 follow-up; taken by delegation).

## Context

ADR-050 sends an invoice whose tax cannot be quoted to `invoicing.tax_quote_queue`, where `TaxQuoteWorker` retries it with `Outcome.retryAt(now + retryDelay)`. It says prolonged outages "trigger queue-age alerts (`BillingBehind`)". That does not hold in the code. A retry sets the item's `due_at` to the next attempt and its `finished_at` to now (`JdbcDurableQueue.complete`), so for an item that keeps retrying:

- `tally_queue_oldest_due_age_seconds` (which `BillingBehind` reads) is at most one retry delay (5 seconds by default), never hours;
- `tally_queue_last_progress_age_seconds` (which `BillingStalled` reads) stays near zero, because every retry is progress;
- the item is never `FAILED`, so `MoneyQueueItemsFailed` never fires.

The existing rules therefore stay silent for the whole of a tax outage, while no invoice is issued and no household is charged. The same is true of a renewal waiting for a blocked account, and the rules' comments already say so.

D6 gives billing four hours to issue an invoice after it falls due (`BillingBehind`).

## Decision

**A new gauge counts from creation.** `tally.queue.oldest_retrying_age_seconds{queue,money}` is the age since creation of the oldest item that is `READY` or `IN_PROCESSING` and has been attempted at least once; zero when there is none. A retry moves `due_at` and `finished_at` forward but never `created_at`, so the gauge keeps growing while an item retries. It is exported for every queue, from `HealthMetrics`, like the other queue gauges.

**One specific alert reads it.** `TaxQuoteStuck`:
- expression: `tally_queue_oldest_retrying_age_seconds{queue="tax-quote"} > 3600`, no `for:` (the gauge is already an age);
- severity `page`, driver `D6`: an invoice that cannot be issued is revenue that cannot be collected, and D6 allows four hours;
- runbook `docs/runbooks/tax-quote-stuck.md`.

**Threshold: one hour.** The retry delay is seconds, so one hour is hundreds of failed retries, not a blip. It leaves three of D6's four hours to act, and it fires well before `BillingBehind` would, if it could. A shorter threshold would page for brief outages of the external tax service, which Tally already absorbs by design (ADR-050); a longer one would use up D6's budget before anyone knows. The threshold lives in the rule, not in code, so operations can change it without a release, with the same unit-test shape.

**No other queue gets an alert from this gauge.** The renewal queue's items also retry (a renewal waiting for a blocked account), and for them waiting is normal (Slice 3 PR-6); the alert selects `queue="tax-quote"` only. A later alert for another queue needs its own record.

## Consequences

- A tax outage longer than an hour pages the billing on-call, who can tell the tax provider's outage from a Tally fault by the runbook.
- The runbook for a stuck tax queue is new (`tax-quote-stuck.md`); F14 requires each rule to name an existing runbook, a firing test and a quiet test.
- `BillingBehind` and `BillingStalled` are unchanged and still do not see retrying items. The gauge's javadoc says why.
- ADR-050 is not edited: its remark about `BillingBehind` is corrected here.

## Rejected

- **Reading `attempts` in the rule:** F14 refuses rules that read attempts (a blocked renewal's attempts count hours, not failures).
- **Changing `retryAt` to keep `due_at`:** the queue's contract is that `due_at` is the time of the next attempt; the claim query depends on it.
- **A counter of failed tax calls:** a new counter in the adapter would see a failed call, not an invoice that is still waiting, and would not survive a restart; the queue table already knows.
- **Paging at four hours:** by then D6 is already missed.

## Drivers

D6; D2-04; ADR-030; ADR-050; FR-023.

## Revisit when

Another queue needs the same alert, or the tax provider's real outage pattern shows one hour pages too often.
