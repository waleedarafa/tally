# Tally drivers, second design

From the Design Record, Step 8. Numbering differs from the first run's `drivers.md` (archived).

**Top three:** correctness, recoverability, deployability. **Constraints:** auditability and privacy.
Measures marked *(assumed)* come from played stakeholders and must be confirmed before launch.

## High value, high risk

### D1. A renewal happens exactly once
- **Source:** the billing schedule. **Stimulus:** a subscription's period ends.
- **Artifact:** Subscription (renewal), Invoicing (invoice).
- **Environment:** two copies of the billing run are active; one crashes halfway.
- **Response:** the period renews once, and one invoice is issued.
- **Measure:** zero duplicates and zero misses across 1,000,000 simulated renewals.
- **Verified by:** AT-9 in CI (scaled); full size nightly.

### D2. A customer is never charged twice
- **Source:** the card provider. **Stimulus:** a charge times out after the card was charged.
- **Artifact:** Payment. **Environment:** normal operation.
- **Response:** the attempt is marked unknown, not retried, and resolved by asking the provider.
- **Measure:** zero double charges; unknown outcomes resolved within 1 hour *(assumed)*.
- **Verified by:** AT-5, AT-15.

### D3. No work is lost in a crash
- **Source:** infrastructure. **Stimulus:** a crash after an invoice is saved, before collect reaches Payment.
- **Artifact:** the hand-off from Invoicing to Payment. **Environment:** a renewal run in progress.
- **Response:** after restart, the collect request is sent exactly once.
- **Measure:** every issued invoice leads to exactly one collect request; none pending longer than 15 minutes *(assumed)*.
- **Verified by:** AT-7, AT-8, AT-17.

## High value, medium risk

| ID | Scenario | Measure | Artifact |
|---|---|---|---|
| D4 | A known kind of offer goes live, set up by marketing, with no release | 2 working days | Offers and Pricing |
| D5 | A new kind of offer ships, changing only Offers and Pricing, behind a flag | 2 weeks | Offers and Pricing |
| D6 | The busiest billing day, 200,000 renewals | within 4 hours *(assumed)*; about 14 a second | Subscription, Invoicing, Payment |
| D7 | An auditor asks why a charge happened | traced to PriceQuote reason, offer version and period within 1 working day | Invoicing, Offers and Pricing |
| D8 | A subscriber asks to be deleted | personal data gone within 30 days; invoices kept as the law requires *(assumed)* | Account, Invoicing, backups |
| D10 | A developer runs 30 days of renewal and failed payment in a test | under 1 minute | all contexts with dates |
| D13 | "Can I watch?" while Invoicing or Payment is down | answered 99.9% of the month *(assumed)* | Access |

## Medium

| ID | Scenario | Measure | Artifact |
|---|---|---|---|
| D9 | A stalled billing run | on-call alerted within 5 minutes | Subscription, Invoicing |
| D11 | A second payment provider | added within one quarter, changing only Payment's translation layer | Payment |
| D12 | Saudi Arabia (SAR, 15% VAT) | added through tax settings and price lists, no code change in Offers and Pricing | Invoicing, Offers and Pricing |

## Proposed: imported from the first run

| ID | Scenario | Measure | From |
|---|---|---|---|
| D14 | Tally's payment records match the provider's, in both directions | every mismatch detected within 24 hours *(assumed)* | first-run D14 |
| D15 | Renewals work without the customer present; an authentication demand reaches the customer | within 1 hour *(assumed)* | first-run D15 |

## Rules that hold everywhere

- **R1** Payment knows nothing about invoices.
- **R2** Invoicing receives amounts; it never applies offer rules.
- **R3** Only Collections sets an account's standing.
- **R4** Offers and Pricing exposes only `Pricing` and `PriceQuote`.
- **R5** The shared kernel holds only Money, Ratio and the event marker.
- **C2** Card numbers never enter Tally.
