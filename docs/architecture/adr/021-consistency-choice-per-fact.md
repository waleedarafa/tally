# ADR-021. Consistency is chosen per fact

**Status:** Proposed. Waiting for business approval of the Entitlement rule.

## Context
CAP only forces a choice during a partition, and the right choice depends on the business cost of being wrong.

## Decision
| Situation | Choice |
|---|---|
| Unsure whether a charge succeeded | Consistency. Never guess; reconcile (ADR-018). |
| Payment status right after checkout | Read-your-writes, from Payment. |
| Entitlement cannot answer (for example, during database failover) | Availability. Let the customer in. |
| Support's billing history | Eventual consistency. |
| Invoice "paid" status in Invoicing | Eventual; reconciled daily against Payment. |

Business justification: user satisfaction. Paying customers are never locked out by our own failure.

## Consequences
During an outage, blocked customers may get in too. The business accepts some free access in exchange for never locking out paying customers.

## Compliance
Failover test in staging. Daily internal reconciliation with an alert on any difference.

## Drivers
D3, D10. Business goal 1 trades against availability.

## Revisit when
Tally sells products where unpaid access is expensive.

## Notes
Recorded retroactively in the Step 7 review; decided in Step 6. Must not be self-approved.
