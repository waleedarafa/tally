# ADR-031. Daily reconciliation against the provider's settlement report

**Status:** Accepted. Built in slice 2.

## Context
Spike B case B4: the provider declined a charge but reported success. Tally marked the invoice `PAID` and kept access. Reconciliation never re-checks a success, so nothing detected it. The same shape happens in real payments when a success changes later: a refund in the provider's dashboard, a chargeback, a slow payment method that fails, or a provider rolling a failed request forward after an incident.

Options considered: re-check every success after 5 minutes; reconcile daily against the settlement report; receive provider notifications (webhooks).

## Decision
Payment reconciles its attempts daily against the provider's settlement report, in both directions. Every mismatch is recorded, listed and alerted. This record covers **detection only**. What happens to the invoice and to access is ADR-032.

Re-checking every success was rejected: those calls share the provider's rate limit with charges, and on the busiest day they would halve the charging rate (D6). Webhooks are deferred until slice 2's idempotency is in place.

Business justification: cost. Revenue leakage must be found.

## Consequences
- A mismatch can take up to a day to detect.
- The fake provider needs a settlement report and a "reversal" mode.

## Compliance
Automated. `SpikeBReconciliationIT`'s B4 assertion is changed deliberately, to expect detection. A new test covers a reversal one day after success.

## Drivers
D3, D14.

## Revisit when
A real provider offers notifications for status changes: add webhooks for faster detection.

## Notes
Recorded after slice 1, from Spike B (R2, detection part).
