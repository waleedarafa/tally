# ADR-030. Retry a payment call only when no charge is certain

**Status:** Accepted. Extends ADR-018. Planned to be superseded in slice 2.

## Context
ADR-018 forbids retrying an unknown outcome. But some failures prove the provider did not charge. Without retrying those, a provider outage leaves every affected invoice unpaid until slice 2.

Evidence from a reference provider: Stripe's documentation says rate limiters run before its idempotency layer, so a 429 was not executed. It says network errors leave the client not knowing whether the request arrived. It tells clients to treat a `500` as indeterminate, and groups 500, 502, 503 and 504 together as server errors.

## Decision
A payment attempt returns to `PENDING` (it is retried) only when the transport proves no charge happened:
- The connection was refused, or the connect timed out (the request never reached the provider).
- The provider answered 429.
- The provider answered 503. **This is valid only against our fake provider**, whose contract says 503 means "not processed". A Stripe-like provider does not promise this.

Every other failure, including a read timeout, is `UNKNOWN` and is reconciled, never retried (ADR-018).

Business justification: cost. An outage must not leave invoices unpaid, and no customer may be charged twice.

## Consequences
- AT-4 passes in slice 1.
- The 503 rule is unsafe against a real provider. Nothing reaches a real provider before slice 2.

## Compliance
Automated tests with the fake provider's `OUTAGE`, `RATE_LIMIT` and `TIMEOUT_AFTER_CHARGE` modes. `fake-provider/CONTRACT.md` lists the 503 assumption as critical.

## Drivers
D3, D6.

## Revisit when
Slice 2 adds idempotency keys. Then every server error and network error can be retried with the same key, and this record is superseded. Slice 2 adds a fake provider mode "503 after charging" to prove it.

## Notes
Recorded after slice 1 (deviation D7, R4). Reference: https://docs.stripe.com/error-low-level

Archived; see `docs/architecture/v2/README.md`.
