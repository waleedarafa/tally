# ADR-022. Incomplete event publications are resubmitted and monitored

**Status:** Accepted

## Context
The evaluation found that a failed listener leaves its event publication incomplete. Spring Modulith republishes outstanding events on restart only if a property enables it. Otherwise the application must resubmit them through `IncompleteEventPublications`. As designed, a paid invoice could look unpaid until the next deployment, with no alert. Scored 9 (unknown technology).

## Decision
- A scheduled job resubmits incomplete publications older than a few minutes. It runs under ADR-017 and reports health under ADR-023.
- An alert fires if any publication stays incomplete for more than 15 minutes.
- All listeners are idempotent.
- A slice 1 spike verifies the real behaviour before relying on it.

Business justification: cost. Customers who paid are never chased.

## Consequences
A listener may run more than once.

## Compliance
Automated. The slice 1 spike becomes a permanent test: kill the application mid-transaction, make a listener fail, and check every event is processed exactly once in effect.

## Drivers
D2, D7, D13.

## Revisit when
The spike shows different library behaviour than documented.

## Notes
Created from the Step 8 evaluation.

Cross-reference added after slice 1: extended by ADR-028 (event resubmission rules, from Spike A).
