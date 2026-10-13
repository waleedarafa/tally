# Runbook: queue items failed

A queue item fails when its handler throws. It stays `FAILED` and waits for a person: it is never retried by itself, and retention never deletes it (FR-037).

## Who

- **Money queue** (`MoneyQueueItemsFailed`, page): the billing team's on-call engineer.
- **Any other queue** (`QueueItemsFailed`, ticket): the billing team, in working hours.

## When

- **`MoneyQueueItemsFailed`** (page, D1, D2): a money queue (`renewals`, `payment-collection`, `collections-retry`) has a failed item. A renewal, a payment or a retry has not happened.
- **`QueueItemsFailed`** (ticket, FR-017): another queue has had a failed item for 10 minutes.

## See what is wrong

The table is in `billing-stalled.md`'s queue table, or `<schema>.jobs` for the `jobs-*` queues.

```sql
SELECT id, queue_key, due_at, attempts, finished_at, last_error
FROM payment.collection_queue
WHERE status = 'FAILED'
ORDER BY finished_at;
```

The log line `Queue <name> item <key> failed` has the stack trace.

## What to do, by cause

### (a) A bug, or data the handler cannot handle
1. Fix the cause and deploy.
2. Return the item to the queue:
   ```sql
   UPDATE payment.collection_queue
   SET status = 'READY', due_at = now(), last_error = NULL
   WHERE id = <id> AND status = 'FAILED';
   ```
3. Check that it finishes: its status becomes `DONE`.

The queue's duplicate guard (a unique constraint in a business table, named on its `QueueDefinition`) stops the work from being done twice, even if part of it ran before it failed.

### (b) The work was done another way
Mark the item done, and record in the incident ticket who did it, and why the effect is already in place:
```sql
UPDATE payment.collection_queue SET status = 'DONE', finished_at = now() WHERE id = <id> AND status = 'FAILED';
```

## Never

- **Never delete a failed item.** It is the only record that the work has not happened.
- Never return an item to the queue before its cause is fixed: it fails again.
