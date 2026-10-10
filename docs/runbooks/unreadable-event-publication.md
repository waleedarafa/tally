# Runbook: unreadable event publication

An event publication is a row in `event_publication`: one event, for one listener, waiting to be handled or already handled. The resubmission job (`EventResubmission`, ADR-016) retries incomplete publications one at a time. It never resubmits a publication whose event cannot be read; that row waits for a person.

## Who

The billing team's on-call engineer (Design Record §11).

## When

Either alert:

- **`tally.events.unreadable` > 0.** The last resubmission run skipped publications because their stored event could not be read. The log line `Publication <id> cannot be read and is not resubmitted` names each one, with the exception.
- **Incomplete publications older than the threshold** (`tally.events.oldest_incomplete_age_seconds`, FR-022: 15 minutes) **for an event class that no longer exists.** The registry drops such a row before the resubmission job sees it (spike S-1, S1f). It never appears in `tally.events.unreadable`; the log shows `Event '<id>' of unknown type '<class>' found`.

To list what is waiting:

```sql
SELECT id, event_type, listener_id, publication_date, status, completion_attempts
FROM event_publication
WHERE completion_date IS NULL
ORDER BY publication_date;
```

## What to do, by cause

### (a) The event class was renamed or moved

This breaks CLAUDE.md rule 7: an event class is never renamed or moved.

1. Restore the class under its old fully qualified name: the `event_type` the row stores.
2. Release and deploy.
3. The resubmission job resubmits the row on its next run. Check that `completion_date` is set.

### (b) The payload no longer matches the class

For example, a field changed type, or a primitive field was added (FF-13).

1. Fix forward: a release whose event class reads the stored shape. Add a reference-type field, or accept the old type, so that the stored JSON deserializes.
2. Release and deploy.
3. The resubmission job resubmits the row on its next run. Check that `completion_date` is set.

### (c) The listener's effect was already achieved by hand

For example, support issued the invoice another way. Mark the publication completed:

```sql
UPDATE event_publication
SET status = 'COMPLETED', completion_date = now()
WHERE id = '<publication id>';
```

Record in the incident ticket:
- who did it;
- why the effect is already in place, with the evidence;
- the publication ID, its `event_type` and its `listener_id`.

## Never

- **Never delete a publication row.** It is the only record that an event was published and not handled. Deleting it loses that fact, and the alert stops without the cause being fixed.
- Never mark a publication completed unless its effect is in place (case c). Completing it is not a way to silence the alert.
