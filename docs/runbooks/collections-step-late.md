# Runbook: a Collections step is late

A Collections case moves on a timetable counted from its first decline (design.md §5; ADR-037): retries on day 3 and day 7, OVERDUE one hour after the day-7 retry, BLOCKED on day 10, WRITTEN_OFF on day 30. The three steps that change the case's state, and the account's standing with it, are timers on `collections.timer_queue`. Every instance claims due timers from it.

## Who

The billing team, in working hours (a ticket).

## When

**`CollectionsStepLate`** (ticket, ADR-037): the oldest due step on `collections-timers` has been due for over an hour, for 5 minutes.

What it costs: a household that does not pay is made OVERDUE, BLOCKED or written off later than the timetable says, and watches meanwhile. No money is lost, and nothing is done twice. When the step runs, it acts on its rule from the state it finds (a late timer): it first asks Invoicing which invoices are still unpaid, and closes the case if none is.

The retries are on the money queue `collections-retry`: when they are late, `BillingStalled` or `BillingBehind` pages instead (`billing-stalled.md`).

## See what is wrong

```sql
-- The due steps, oldest first, and the cases they belong to.
SELECT q.queue_key, q.status, q.due_at, q.claimed_by, q.claimed_at, q.attempts, q.last_error,
       c.account_id, c.status AS case_status
FROM collections.timer_queue q
JOIN collections.collection_case c ON c.id::text = split_part(q.payload, ',', 1)
WHERE q.status IN ('READY', 'IN_PROCESSING') AND q.due_at <= now()
ORDER BY q.due_at;

-- The steps each case has taken: one row for each case and step (its guard).
SELECT case_id, step, done_at FROM collections.case_step ORDER BY done_at DESC LIMIT 20;
```

In Prometheus:
- `tally_queue_oldest_due_age_seconds{queue="collections-timers"}` and `tally_queue_waiting{queue="collections-timers"}`;
- `tally_queue_last_progress_age_seconds{queue="collections-timers"}`: no progress at all means no instance is claiming;
- `tally_job_last_success_age_seconds{exported_job="queue-poller.collections-timers"}` on each instance.

## What to do, by cause

### (a) No instance claims the timers
`last_progress_age` grows and every row is `READY`. Check the poller on each instance (`JobOverdue` may be raised for `queue-poller.collections-timers` too). Restart the instances whose poller has stopped. The due steps then run, in due order.

### (b) A step is held (`IN_PROCESSING`) for long
A worker holds the claim. The reaper frees a claim older than the claim timeout, and the step runs again: its guard (`case_step_one_per_case_and_step`) and the case's row lock make that safe. A step waits on the case's lock while a long transaction holds it: find it in `pg_stat_activity` (`wait_event_type = 'Lock'`).

### (c) A step fails again and again
It ends `FAILED`, and `QueueItemsFailed` raises a ticket. `last_error` names the cause. Fix it, then follow `queue-items-failed.md`, which returns a failed item to the queue once its cause is fixed.

## Never

- Never set an account's standing by hand to make up for a late step. Only Collections sets the standing (R3, F3), in the same transaction as the case's state (rule 5). A standing set by hand is undone or contradicted by the next step.
- Never move a `READY` timer's `due_at` to make it run sooner, delete a timer, or delete or insert a `case_step` row. A `case_step` row is the guard that a step is taken once.
- Never read a queue's `attempts` as failures. A renewal that waits while its account is BLOCKED returns to the queue every hour (Slice 3 PR-6), so its attempts count hours of waiting. Failures are `tally_queue_failed`.
