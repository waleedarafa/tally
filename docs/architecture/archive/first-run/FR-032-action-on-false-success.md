# ADR-032. What happens when a reported success proves false

**Status:** Proposed. Waiting for finance.

## Context
ADR-031 detects a payment that Tally recorded as successful but the provider did not collect. The invoice is `PAID` and the customer has access. Someone must decide what the customer experiences next.

## Options
- **A. Reopen the invoice** (`PAID` → `PAYMENT_FAILED`) and let dunning handle it (slice 5). Access continues until dunning blocks it.
- **B. Flag for human review only.** Nothing changes for the customer until finance acts.
- **C. Block access immediately.** Fastest recovery, but it punishes customers for a mistake that is not theirs.

## Recommendation
A, with B for amounts above a set threshold. The customer is treated like any other failed payment, through one process.

## Consequences
Until slice 5, option A only reopens the invoice. No automatic follow-up exists yet.

## Compliance
To be defined with finance.

## Drivers
D14. Business goal 1.

## Notes
Created after slice 1 (R2, action part). Must not be self-approved: it changes what a customer sees after paying.

Archived; see `docs/architecture/v2/README.md`.
