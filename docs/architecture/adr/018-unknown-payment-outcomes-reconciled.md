# ADR-018. Unknown payment outcomes are reconciled, never retried

**Status:** Accepted. Planned to be superseded in slice 2.

## Context
A timed-out charge may or may not have happened. Before idempotency keys exist, a blind retry may charge the customer twice.

## Decision
A timed-out attempt is marked `UNKNOWN`. It is never retried. A reconciliation job asks the provider for the real outcome, on a backoff schedule (5 minutes, 1 hour, then daily), and records it. The job also re-checks `FAILED` attempts from the last 48 hours, because a provider can report a failure for a charge that went through. A corrected outcome publishes the matching event. Invoicing and Collections act only on the resolved outcome.

Business justification: user satisfaction and legal risk. A double charge is the most expensive mistake Tally can make.

## Consequences
- Some payments are delayed until reconciled.
- The reconciliation job is load-bearing: D3 depends on it.

## Compliance
Automated. The fake provider's "time out after charging" and "report the wrong outcome" modes. An alert on `UNKNOWN` attempts older than 1 hour. A health signal for the job (ADR-023).

## Drivers
D3, D13.

## Revisit when
Slice 2 adds idempotency keys. A new record will allow safe retries with exponential backoff, and supersede this one.

## Notes
Split out of ADR-005 in Phase 0, because it changes on a different schedule. Scope widened to recent `FAILED` attempts while writing the slice 1 brief (still Phase 0, before any code).
