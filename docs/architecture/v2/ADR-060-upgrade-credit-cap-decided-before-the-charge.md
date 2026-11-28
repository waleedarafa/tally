# ADR-060. An upgrade's credit cap is decided before the charge and stored on the intent

**Status:** Accepted, 28 November 2026 (review of #97, bugs 1 and 2; taken by delegation, Waleed 2026-11-18 "Feel free to take decisions").

## Context

ADR-051 makes an upgrade's credit tax-inclusive and says a credit note "is still capped by what is available on the invoice it corrects ... capped by that rule, not refused". The review of #97 found that the cap was only applied in `CreditNote.issueWithId`, after the charge: it throws instead of capping, inside `completeAfterCharge`, so a household is charged and the upgrade never completes. Example: an invoice of 1000 with no VAT line (issued before tax), then an upgrade at 5% credits 1000 + 50 = 1050 against 1000 available. The same happens if the rate rises between the period invoice and the upgrade.

The review also found that the "nothing to collect" check used the net total, while the amount sent to Payment is the gross `charge()`, which can be zero or less after per-line half-up VAT.

ADR-057 already refuses, before charging, a credit whose NET amount is larger than what is left (review of #95, finding 2).

## Decision

- **The cap is decided when the intent is placed, before Payment is called.** If the net credit fits but the net credit plus its VAT does not, the credit's VAT is capped so the credit equals exactly what is left on the invoice being left. The cap is stored on the intent (`credit_cap_minor`, new migration V62, null when there is no cap), so a retry or a race loser charges the same amount and the credit note issued after the charge fits.
- **The amount charged follows the capped credit:** `charge()` = net charge + charge VAT − (net credit + capped credit VAT).
- **A net credit larger than what is left is still refused** before charging (ADR-057, unchanged).
- **The gross charge must be positive.** If `charge()` is zero or less, the upgrade is refused before the intent is placed and before Payment is called.

## Consequences

- A household whose old invoice carried less VAT than the credit would reverse pays that difference on the upgrade instead of being refused; the credit never exceeds what was invoiced.
- One more nullable column on `invoicing.upgrade_intent`; intents placed before V62 have no cap and behave as before.

## Rejected

- Refusing the upgrade when the credit plus VAT does not fit: ADR-051 says capped, not refused, and the case it names (an invoice issued before tax) would block upgrades for those households.
- Capping only in `completeAfterCharge`: the amount was already charged with the uncapped credit, so the documents would not add up to what was collected.
