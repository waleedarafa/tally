# ADR-002. Invoicing owns the billing calendar

**Status:** Accepted

## Context
Billing period dates are needed by Subscription (when does a period end?) and by Invoicing (how much to prorate?). If both modules calculate them, the same algorithm lives in two places. Kill Bill users reported this failure in production: an invoice preview ignored the billing cycle day, so customers saw one amount and paid another.

Options considered: Subscription owns the calendar and publishes "period renewed"; Invoicing owns the calendar and schedules each next billing date.

## Decision
We will let Invoicing calculate and schedule every billing date. Subscription records business changes only: started, plan changed, cancelled. Any price preview calls Invoicing's API; nothing recalculates amounts elsewhere.

Business justification: user satisfaction and cost. A mismatch between the previewed and the charged price creates support calls and refunds.

## Consequences
- All billing date logic has one owner.
- Invoicing grows larger.
- "Paid until when?" is an Invoicing question.
- The "period renewed" event no longer exists. D1 is phrased per billing date.

## Compliance
Automated. An ArchUnit rule forbids period and proration calculations outside the `invoicing` module. Calendar edge cases (31st, 29 February, daylight saving) are covered by the D4 test suite.

## Drivers
D1, D4, D10.

## Revisit when
A second consumer needs the billing timeline directly (for example, revenue reporting). Then consider extracting the timeline as an open-host service, as Kill Bill did with `junction`.

## Notes
Decided in Step 3, Part B.

Archived; see `docs/architecture/v2/README.md`.
