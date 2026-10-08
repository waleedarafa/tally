# ADR-024. Releasing changes that touch money

**Status:** Accepted

## Context
You cannot roll back a charge, and payment bugs often return success with the wrong amount. But a month of shadow running for every pricing change would contradict D5 (promotion live in 2 days). The evaluation found this collision.

## Decision
- **New calculation logic** (proration rules, price calculation code, payment adapters) ships switched off. It runs in shadow mode for a full billing cycle: both old and new results are calculated, the old one is charged, and differences are logged. Then it is released by flag, by account: internal accounts, 1%, 10%, everyone.
- **A promotion using an existing rule type** is data. It needs a preview against sample accounts and a flag, but no shadow cycle.
- Flags use the OpenFeature API.
- Business facts are monitored, not only errors: collected versus invoiced totals, zero or negative invoices, average amount per plan, duplicate invoices.

Business justification: user satisfaction and time to market together.

## Consequences
- Calculation changes take a month to release fully.
- Promotions of known types go live in days.

## Compliance
Automated business invariant checks, daily. Shadow-difference reports reviewed before each release.

## Drivers
D3, D5, D10.

## Revisit when
Marketing repeatedly needs promotions with new calculations: the deferred rules-engine decision has fired.

## Notes
Split out of ADR-007 in Phase 0, with the scope correction from the Step 8 evaluation.

Cross-reference added after slice 1: the collected-versus-invoiced check is implemented by ADR-033.
