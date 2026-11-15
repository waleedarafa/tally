# Runbook: events stuck

An event publication is one event for one listener (ADR-002). A listener that fails leaves its publication incomplete; the resubmission job (`event-resubmission`, every minute) retries incomplete publications older than 5 minutes, one at a time (ADR-016).

## Who

- **`EventsStuck`** and **`EventListenerGone`** (page): the billing team's on-call engineer.
- **`ListenerFailing`** (ticket): the billing team, in working hours.

## When

- **`EventsStuck`** (page, D3): a publication has been incomplete for over 15 minutes. The work it carries (an invoice issued, a collection requested) has not happened.
- **`ListenerFailing`** (ticket, D3): a listener failed in the last 15 minutes. Resubmission retries it; this alert says something is failing before it becomes stuck.
- **`EventListenerGone`** (page, D3): for 5 minutes, publications have waited for a listener this release does not have (`tally_events_listener_gone`). A listener was removed, renamed or moved while it had incomplete publications (ADR-028; CLAUDE.md rule 7). Spring Modulith marks each `FAILED` and never runs it again (`ListenerGoneIT`): its work is lost unless a person acts.

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

### (d) The listener is gone (`EventListenerGone`)
Find the listener and how much waits for it:

```sql
SELECT listener_id, count(*), min(publication_date)
FROM event_publication
WHERE completion_date IS NULL
GROUP BY listener_id
ORDER BY listener_id;
```

The listener this release lacks is the one missing from `tally/src/test/resources/architecture/pinned-listeners.txt`. Then, by what the listener did:
- **Its work is no longer needed** (a module that was removed on purpose, as the in-process `access` module in Slice 2 PR-9): record the decision in the incident ticket, then mark those publications completed. Their effect is no longer wanted.
- **Its work is still needed** (a rename or a move by mistake): deploy a release that has the listener again, under its old identity; resubmission then runs the publications. Rule 7 says how to move a listener: the new one first, then the old one once it has no incomplete publication.

## Never

- **Never delete a publication row.** It is the only record that the event's work has not happened.
- Never mark a publication completed unless its effect is in place, or, for a listener removed on purpose, its effect is no longer wanted and that decision is in the incident ticket (d).
