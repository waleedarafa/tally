# ADR-016. Tax calculation is bought, behind an interface

**Status:** Accepted

## Context
Tax rules change when governments change them. Tax calculation does not differentiate Tally.

## Decision
We will use an external tax service, behind a `TaxCalculator` interface owned by Invoicing, with an anticorruption layer translating the vendor's model.

Business justification: cost and risk. The vendor tracks rule changes.

## Consequences
- Each renewal depends on one more external service. It needs the same isolation as the payment provider.
- A per-transaction fee.

## Compliance
Module tests against a fake tax service. Built in slice 6.

## Drivers
D9.

## Revisit when
Tally expands to only two or three countries with stable tax rules: keeping rules as data inside Invoicing may then be cheaper.

## Notes
Recorded retroactively in the Step 7 review; decided in the Lesson 1 worked example.

Archived; see `docs/architecture/v2/README.md`.
