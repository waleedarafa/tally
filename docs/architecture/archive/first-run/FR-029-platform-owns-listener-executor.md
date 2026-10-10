# ADR-029. The platform provides the event-listener executor

**Status:** Accepted. Clarifies ADR-027.

## Context
In slice 1, Spring Boot created the thread pool that runs Spring Modulith's asynchronous listeners. Its waiting work sat in an in-memory queue that no Tally metric could see. ADR-027 says the platform owns threads for background work.

## Decision
The `platform` module provides the executor for event listeners, and Spring Boot does not create its own. Size: `tally.events.listener-threads` (default 8). Metrics: `tally.events.listeners.{active, queued, threads, completed, failed, last_success_age_seconds}`.

Business justification: cost. Waiting events are visible before they become late payments.

## Consequences
- One more pool to size. Its size limits how many listeners run at once.
- If the instance crashes, work waiting in this in-memory queue is recovered by resubmission (ADR-028), not by the pool.

## Compliance
Automated. FF-6 forbids executor creation outside `platform`. FF-10b checks the listener pool reports health.

## Drivers
D2, D7, D13.

## Revisit when
Listener latency grows under load, or the pool saturates in the nightly test.

## Notes
Recorded after implementation in slice 1b (commit 74c663f).

Compliance status after slice 1b: FF-10b does not cover the listener pool, because it is not a registered job. `EventListenerExecutionIT` checks its health metrics instead.

Archived; see `docs/architecture/v2/README.md`.
