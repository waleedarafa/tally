# ADR-019. Access starts with the subscription; checkout waits briefly, then polls

**Status:** Proposed. Waiting for product and finance approval.

## Context
At checkout a person is waiting. Charging synchronously couples checkout to the provider. Fully asynchronous checkout feels slow in the common case, where payment succeeds in under a second.

## Decision
- Checkout waits up to a few seconds for `PaymentSucceeded`. If it arrives, the customer sees success. If not, the page shows "payment processing" and polls a status endpoint, which reads from Payment (read-your-writes).
- Access is granted when the subscription starts, before the first payment succeeds. If payment later fails, Collections blocks access.

Business justification: user satisfaction. Honest customers never wait.

## Consequences
A customer who never pays gets free access until Collections blocks them (day 10 under ADR-011). Product and finance must accept this exposure.

## Compliance
Integration tests for fast success, slow provider and failed payment at checkout.

## Drivers
D3, D7. Business goal 1 (revenue) trades against user experience.

## Revisit when
Fraud data shows abuse of free access, or Tally sells higher-value products.

## Notes
Recorded retroactively in the Step 7 review; decided in Step 4. Must not be self-approved: it trades revenue for customer experience.
