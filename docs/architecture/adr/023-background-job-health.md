# ADR-023. Every background job reports its health

**Status:** Accepted

## Context
The evaluation's first risk theme: Tally's correctness depends on background jobs (reaper, reconciliation, event resubmission, dunning scheduler) that can fail silently.

## Decision
Every background job and queue reports when it last succeeded and how much work is waiting. An alert fires when a job is overdue by twice its interval (minimum 5 minutes), or when a queue's oldest due item is 5 minutes late.

Business justification: cost. Silent failures in billing turn into lost revenue or wrongful charges.

## Consequences
Each new job needs a health signal before it can merge.

## Compliance
Automated. A fitness function fails the build if a scheduled job lacks a health signal. A weekly chaos test disables each job and checks its alert.

## Drivers
D7, D13.

## Revisit when
False alarms exceed one a week.

## Notes
Created from the Step 8 evaluation.
