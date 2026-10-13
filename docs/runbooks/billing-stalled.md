# Runbook: billing stalled or behind

The billing run is three money queues and the event listeners between them (renewal, then invoice, then collection request, then payment). Each queue is a table; every instance claims due items from it (FR-017).

## Who

The billing team's on-call engineer (Design Record §11).

## When

- **`BillingStalled`** (page, D9): a money queue (`money="true"`) has work due for over 3 minutes and has finished nothing for over 3 minutes. Stall to alert: 4m30s.
- **`ListenersStalled`** (page, D9): event publications have waited over 3 minutes and no listener has succeeded for over 3 minutes. Invoices are issued and collection requests made by listeners, so the run stops between queues.
- **`BillingBehind`** (page, D6): a money queue's oldest due item is over 4 hours old. The run is moving, but too slowly to finish the busiest day in time.
- **`PaymentRequestedStuck`** (page, D2): a payment attempt has waited over 15 minutes to be sent (ADR-018). Tally held it back (its rate permit) or no worker took it.

## See what is wrong

The queue in the alert's `queue` label, and its table:

| Queue | Table |
|---|---|
| `renewals` | `subscription.renewal` |
| `payment-collection` | `payment.collection_queue` |
| `collections-retry` | `collections.retry_queue` |

```sql
-- What is due, held and finished; replace the table.
SELECT status, count(*), min(due_at) AS oldest_due, max(finished_at) AS last_finished
FROM payment.collection_queue
WHERE due_at <= now() OR status IN ('IN_PROCESSING', 'FAILED')
GROUP BY status;

-- Items a worker holds, oldest first: a hung worker shows here.
SELECT id, queue_key, claimed_by, claimed_at, attempts
FROM payment.collection_queue
WHERE status = 'IN_PROCESSING'
ORDER BY claimed_at;
```

In Prometheus:
- `tally_job_last_success_age_seconds{exported_job=~"queue-poller.*|queue-reaper.*"}`: the pollers that claim, and the reapers that free stale claims, on each instance;
- `tally_payment_breaker_state`: 1 or 2 means the provider breaker is not closed, and `payment-collection` claims nothing while it is open;
- `tally_events_listeners_queued`, `tally_events_listeners_active`, `tally_events_incomplete`: the listener pool.

Log lines: `Queue <name>: poll failed`, `Queue <name>: reaper failed`, `Event listener <class>.<method> failed`.

## What to do, by cause

### (a) The provider is down
`tally_payment_breaker_state` is not 0 and `payment-collection` is the stalled queue. Claims stop on purpose while the breaker is open (ADR-013). Follow `provider-outage.md`. Work resumes by itself when the provider recovers.

### (b) No instance is polling
The poller jobs' last success ages on every instance, or no instance is up (`TallyDown`). Restart the instances. Claims left `IN_PROCESSING` return to `READY` when the reaper runs after the claim timeout (`tally.platform.claim-timeout`, 5 minutes).

### (c) Workers are hung
Items stay `IN_PROCESSING` and `claimed_at` keeps ageing. Take a thread dump of the instance in `claimed_by` (`jcmd <pid> Thread.print`), then restart it. The reaper returns its claims.

### (d) The database is slow or locked
```sql
SELECT pid, state, wait_event_type, wait_event, now() - query_start AS running, left(query, 120)
FROM pg_stat_activity
WHERE datname = current_database() AND state <> 'idle'
ORDER BY running DESC;
```
Find the blocking session. Escalate to the database owner before ending any session.

### (e) Listeners are stalled
The pool is full (`tally_events_listeners_queued` keeps growing) or every listener fails. Check the log for `Event listener ... failed`, then follow `events-stuck.md`.

### (f) Behind, not stalled (`BillingBehind`)
Items finish, but the oldest due item keeps ageing. Add instances; check whether the provider's rate (`tally.payment.provider.rate-limit-per-second`) is the limit.

### (g) An attempt waits to be sent (`PaymentRequestedStuck`)
```sql
SELECT id, reference, created_at FROM payment.payment_attempt
WHERE state = 'REQUESTED' ORDER BY created_at LIMIT 20;
```
If the breaker is open, see (a). Otherwise, check the `payment-collection` queue as above.

## Never

- **Never set a money queue's items to `DONE` by hand.** That skips the work: a renewal not renewed, a payment not collected.
- **Never delete queue rows.** A row is the only record of work still to do.
- Never restart every instance at once while workers hold claims; restart one at a time.
