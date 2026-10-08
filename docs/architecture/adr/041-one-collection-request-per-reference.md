# ADR-041. One collection request per reference

**Status:** Accepted. Extends ADR-014 and ADR-037.

## Context
The slice 1b audit found two related gaps:
- The listener that turns `InvoiceIssued` into a collection request checks for an existing attempt, then inserts. "Check, then act" breaks ADR-028.
- The only database guard is a partial index: one **open** attempt per reference. Once an attempt finishes, nothing stops a second attempt for the same reference. A late duplicate delivery could create a second attempt, and so a second charge.

The cause is a missing concept. "One open attempt per reference" is a concurrency rule. The business rule is different: **an invoice is collected through one collection request.** Slice 2's retries will create several attempts for one request, so attempts cannot carry that rule.

## Decision
Payment gets a `CollectionRequest` aggregate:
- One `CollectionRequest` per reference, enforced by a full unique constraint on `reference`. It is never deleted.
- `PaymentApi.requestCollection(...)` is an atomic insert-or-return (`INSERT ... ON CONFLICT DO NOTHING`, then read). A duplicate call returns the existing request. The API signature does not change.
- Each `PaymentAttempt` belongs to one request. The partial index (one open attempt per reference) stays as a second guard.
- The payment queue names the new constraint as its permanent guard (ADR-037).
- The request holds the overall outcome. Attempts hold the outcome of each try.

Business justification: user satisfaction and legal risk. No duplicate delivery, at any time, can lead to a second charge.

## Consequences
- An additive migration (ADR-025): a new table, backfilled from existing attempts.
- Slice 2 builds on it: idempotency keys and retries are per attempt, inside one request.
- Richardson's aggregate rules apply: outside code refers to the request by reference, and one transaction changes one request.

## Compliance
Automated. A test delivers `InvoiceIssued` twice: at the same moment, and again after the first attempt has finished. Both must leave exactly one request, one attempt and one provider charge. `ConcurrentDeliveryIT`-style test for the collection listener.

## Drivers
D3, D14.

## Revisit when
Never on its own; slice 2 extends it.

## Notes
Created after the slice 1b audit.
