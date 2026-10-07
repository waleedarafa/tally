# ADR-011. Collections orchestrates dunning

**Status:** Proposed. Waiting for finance to approve the dunning policy.

## Context
A failed renewal payment starts a process lasting up to 30 days, involving Payment, Invoicing, Entitlement and Subscription. Finance owns the policy. Choreography would spread the policy across four modules and create cycles.

## Decision
- The happy path is choreographed: `InvoiceIssued` leads to a collection request; `PaymentSucceeded` marks the invoice paid.
- The failure path is orchestrated by one dunning case per account in Collections.
- Collections decides when and whether. It never decides how.
- The case records each step it has taken. No account reaches BLOCKED without passing WARNED.
- Steps are ordered from most to least reversible: retries, warning, block access (day 10), cancel and write off (day 30).
- Countermeasures: semantic lock (`PAYMENT_PENDING`), one open attempt per reference, account credits for overpayments, re-read before every step, step numbers on commands to Entitlement, human confirmation above a set amount.

Business justification: cost (recovered revenue) and user satisfaction (no wrongful blocks).

## Consequences
- The policy is one state machine, readable and testable as one unit.
- Collections depends on four modules; none depends on Collections.

## Compliance
State machine tests for every path, including payment arriving during each step. Collections' scheduled work follows ADR-017 and reports health per ADR-023.

## Drivers
D1, D3, D7, D13.

## Revisit when
Finance changes the policy, or wants different policies per segment.

## Notes
Decided in Step 6. Must not be self-approved: the policy and its customer impact belong to finance.
