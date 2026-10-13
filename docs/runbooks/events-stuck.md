# Runbook: events stuck

An event publication is one event for one listener (ADR-002). A listener that fails leaves its publication incomplete; the resubmission job (`event-resubmission`, every minute) retries incomplete publications older than 5 minutes, one at a time (ADR-016).

## Who

- **`EventsStuck`** (page): the billing team's on-call engineer.
- **`ListenerFailing`** (ticket): the billing team, in working hours.

## When

- **`EventsStuck`** (page, D3): a publication has been incomplete for over 15 minutes. The work it carries (an invoice issued, a collection requested) has not happened.
- **`ListenerFailing`** (ticket, D3): a listener failed in the last 15 minutes. Resubmission retries it; this alert says something is failing before it becomes stuck.

## See what is wrong

```sql
SELECT id, event_type, listener_id, publication_date, completion_attempts, status
FROM event_publication
WHERE completion_date IS NULL
ORDER BY publication_date;
```

The log line `Event listener <class>.<method> failed; its publication stays incomplete for resubmission` has the exception.

In Prometheus: `tally_job_last_success_age_seconds{exported_job="event-resubmission"}` (is resubmission running?) and `tally_events_unreadable` (see `unreadable-event-publication.md`).

## What to do, by cause

### (a) The listener fails every time
Read the exception. Fix forward and deploy; resubmission completes the publication on its next run. Check that `completion_date` is set.

### (b) Resubmission is not running
`event-resubmission` is overdue: follow `job-overdue.md`.

### (c) The publication cannot be read
Follow `unreadable-event-publication.md`.

## Never

- **Never delete a publication row.** It is the only record that the event's work has not happened.
- Never mark a publication completed unless its effect is in place.
