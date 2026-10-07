# ADR-005. Payment execution is asynchronous and isolated

**Status:** Accepted

## Context
The payment provider is Tally's only network dependency. It is rate limited, and it can be slow or unavailable. A slow dependency can freeze its callers, and can exhaust shared resources such as threads and database connections.

## Decision
We will isolate payment execution:
- `PaymentApi.requestCollection(...)` only records a pending attempt and returns.
- Payment workers claim attempts and call the provider with no database transaction open: claim and commit, call, then record the result.
- Every call has a timeout (1 s connect, 5 s read).
- A per-instance rate limiter allows the provider's limit divided by the instance count. HTTP 429 responses trigger backoff.
- A circuit breaker stops workers claiming new attempts while the provider fails.
- Payment workers have their own thread pool (bulkhead).

Business justification: user satisfaction. A provider problem must never reach checkout or support pages.

## Consequences
- Provider failures cannot spread to the rest of Tally.
- Payments are eventually consistent with invoices.
- The rate limit depends on the instance count in ADR-006. Changing one requires checking the other.

## Compliance
Automated integration tests against the fake provider in slow, failing and rate-limiting modes. Weekly chaos test in staging with the provider switched off mid-run.

## Drivers
D3, D6, D7, D8.

## Revisit when
More than two instances, or frequent 429 responses: consider a shared rate limiter across instances.

## Notes
Restructured in Phase 0. The rule for unknown outcomes moved to ADR-018, because it will change in slice 2 while the rest of this record will not.
