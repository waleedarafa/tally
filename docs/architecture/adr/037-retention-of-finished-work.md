# ADR-037. Retention of finished work

**Status:** Accepted, with an assumed period.

## Context
Finished queue rows and completed event publications were never deleted. Every poll scans the queue tables, so polling would slow down silently as they grow. Queue payloads can also hold personal data.

Slice 1b found a subtle effect: deleting a finished queue row frees its key. That key then no longer blocks a duplicate enqueue.

## Decision
- An hourly retention job, run through the platform's job queue, deletes finished queue rows and completed event publications older than `tally.retention.keep` (default 30 days, assumed).
- Failed rows are never deleted automatically. A failed row in a money queue raises an alert.
- **Queue keys are not the permanent guard against duplicates.** Every queue's duplicate protection must also exist as a unique constraint in a business table, which is never deleted. For example: invoices are unique per (account, billing date); payment attempts allow one open attempt per reference.

Business justification: cost. Polling stays fast, and we keep less personal data (D11).

## Consequences
- A queue whose only duplicate guard is its own key becomes unsafe after its rows are deleted. The rule above prevents that.
- Debugging data older than 30 days lives in business tables and logs, not in queue rows.

## Compliance
Automated. The retention job reports health (ADR-023). Manual review of every new queue: its business guard must be named in the queue's registration.

## Drivers
D6, D7, D11.

## Revisit when
Operations or legal need a different period.

## Notes
Recorded after implementation in slice 1b (commit d795cb2). Answers Q5.

Compliance status after slice 1b:
- Every queue names its guard at registration; registration fails without a real unique constraint on a business table.
- The payment guard is a partial index covering only open attempts, so it is not a permanent guard. ADR-041 replaces it with a full unique constraint.
- The failed-row alert and the idea of a money queue do not exist yet. Queues are flagged as money queues, and alert rules are built as code, with staging (slice 2, step 0).
