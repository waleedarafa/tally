# Runbook: job overdue

Every background job reports its last success; a job is overdue when that is older than twice its interval, and never sooner than 5 minutes (FR-023).

## Who

The billing team, in working hours (a ticket).

## When

**`JobOverdue`** (ticket, FR-023): `tally_job_overdue` has been 1 for 5 minutes. The `exported_job` label names the job.

## See what is wrong

In Prometheus:
- `tally_job_overdue == 1` lists the overdue jobs;
- `tally_job_last_success_age_seconds` gives each one's age;
- `tally_job_waiting` gives the work waiting for it.

The periodic jobs' rows:

```sql
SELECT queue_key, status, due_at, last_success_at, last_error FROM payment.jobs
UNION ALL SELECT queue_key, status, due_at, last_success_at, last_error FROM invoicing.jobs
UNION ALL SELECT queue_key, status, due_at, last_success_at, last_error FROM platform.jobs
ORDER BY last_success_at NULLS FIRST;
```

The log line `Periodic job <name> failed` has the exception.

## What to do, by cause

### (a) A poller or reaper (`queue-poller.*`, `queue-reaper.*`)
These run inside every instance. One that is overdue on one instance only means that instance's poller has stopped: restart that instance. If a money queue is behind too, `BillingStalled` pages; follow `billing-stalled.md`.

### (b) A periodic job (`payment-reconciliation`, `event-resubmission`, `retention`, `invariant.*`)
`last_error` holds the last failure. A job that fails is rescheduled at its next interval: fix the cause, and the next run succeeds. A row stuck `IN_PROCESSING` is freed by the reaper after the claim timeout.

## Never

- Never move a job row's `due_at` to make it run now; restart the instance, or wait for the interval.
