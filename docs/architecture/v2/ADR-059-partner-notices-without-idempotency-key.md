# ADR-059. Partner notices are idempotent by their partner sequence, not by an Idempotency-Key

**Status:** Accepted, 28 November 2026 (review of #97, nit 2; taken by delegation, Waleed 2026-11-18 "Feel free to take decisions").

## Context

CLAUDE.md rule 13 says every request that changes state over REST requires an `Idempotency-Key` (ADR-019), handled by the platform's `IdempotentEndpoints`, with one key table per module. ADR-056 added `POST /v1/partners/{partnerId}/notices` to the Access deployable without one. The review of #97 asked to either require the key or raise the conflict for a decision.

Two facts shape the answer:

1. `IdempotentEndpoints` and its key tables belong to Tally's platform. Access is its own deployable (ADR-056 decision 1) and does not use that platform; requiring the key there means a second implementation of ADR-019 plus a new key table in Access.
2. A partner notice already carries its own identity: (partner, bundle, partner sequence). ADR-056 decision 4 applies a notice only when its partner sequence is higher than the stored one, in one statement. A repeat or an older notice changes nothing and is answered 202, so a partner's retry is safe without a key.

## Decision

- Partner notices are exempt from rule 13. Their idempotency is the partner sequence (ADR-056 decision 4), not an `Idempotency-Key`.
- The endpoint ignores an `Idempotency-Key` header if a partner sends one; it does not require it.
- The exemption covers only Access's partner notice endpoint. Every state-changing endpoint of Tally's modules keeps rule 13.
- CLAUDE.md is not edited by this record; rule 13 is read together with this exemption.

## Consequences

- A partner retries a notice by sending it again, unchanged. Nothing is stored per request.
- A notice that reuses a partner sequence with different content (for example the same sequence with `ENDED` instead of `ACTIVATED`) is not detected: it is treated as a repeat and changes nothing. Rule 13's key-reuse check (422) would have caught it. Partner reconciliation (open question 3 of ADR-056) is where such a mismatch is found.
- If a partner later needs request-level replay answers (for example to read back the outcome of a refused notice), this record is revisited and Access gets its own key table.

## Rejected

- Requiring an `Idempotency-Key` on partner notices now: it adds a second ADR-019 implementation and a key table to Access for no extra safety over the partner sequence, and partners would have to send two identities for one notice.
