# ADR-034. Checkout is a process module

**Status:** Accepted. Extends ADR-013.

## Context
Slice 1 placed checkout in Invoicing, because Invoicing was the only module already allowed to use Account, Subscription and Payment. But checkout is a user journey: record the card token, subscribe, wait for payment. Invoicing calculates and records what is owed. They change for different reasons. In ADR-008's growth path, the signup journey belongs to the Subscriber lifecycle team, not Billing.

## Decision
`checkout` is a top-level **process module**, not a bounded context. It has no domain model and owns no business data. It calls the public APIs of `account`, `catalog`, `subscription`, `invoicing` and `payment`. Nothing depends on it.

Business justification: time to market. The signup journey changes without touching invoicing logic.

## Consequences
- Its instability is 1, which is correct for code at the top of a call chain.
- Process modules are a new kind of module. Each one needs its own record.
- Owned by the Subscriber lifecycle team in ADR-008's growth path.

## Compliance
Automated. FF-1 and FF-11 (ADR-040) check its declared dependencies. FF-2 includes it. A rule forbids any module from depending on `checkout`.

## Drivers
D5. Course goal: fast flow.

## Revisit when
Checkout gains business rules of its own (for example, fraud checks). Then it may become a bounded context.

## Notes
Recorded after implementation in slice 1b (commit 605fbed). Added `InvoicingApi.invoicesForSubscription`.

Compliance status after slice 1b: no dedicated rule exists. Modulith verification and FF-11 (ADR-040) enforce it: making any module depend on `checkout` requires editing the approved dependency list, which is reviewed. That is sufficient; no extra rule is planned.
