# ADR-057. An upgrade intent closes when it cannot complete, and a new attempt charges a fresh reference

**Status:** Accepted, 28 November 2026 (review of #95, findings 1, 3 and 4; taken by delegation).

## Context

ADR-041 and ADR-044 keep an upgrade intent between the charge and the documents, keyed by the adjustment's payment reference. The review of #95 found three gaps:

1. A resolved decline left the intent open for ever. A retry to the same plan re-requested the same reference, which Payment ignores, so nothing was charged; a retry to another plan was refused as already in progress.
2. Two upgrades at the same time were check, then act. Both saw no open intent, derived the same reference, and the loser returned as if charged (CLAUDE.md rules 7 and 11).
3. `completeAfterCharge` ignored `changePlan` returning false (for example when Collections ended the subscription in between) and still issued a paid adjustment and a credit note.

ADR-041 D1 ("a retry of the same upgrade charges the same reference") is about a retry while the charge's outcome is not known. It does not say what happens after a resolved decline. ADR-054 fixes the adjustment and credit-note IDs; it does not fix the payment reference of a later attempt.

## Decision

- **One open intent per period invoice.** A named partial unique index, `upgrade_intent_one_open_per_period_invoice` on `period_invoice_id` where the intent is neither completed nor closed (V61). `UpgradeIntents.place` reports whether it inserted; only that index or the primary key count as "already there", any other failure is thrown. A caller that loses the race charges the stored intent, with its reference and its stored amount; a different plan is refused as already in progress.
- **A resolved decline closes the intent** (`closed_reason = DECLINED`). Only a resolved outcome (SOFT or HARD) closes it; UNKNOWN does not (FR-018). Collections does not retry an upgrade charge: the household asks again.
- **A new attempt charges a fresh reference.** The first attempt keeps `INV-<adjustment id>` (ADR-054). The next attempts of the same adjustment use `INV-<adjustment id>-retry-<n>`, n = 2, 3, ... The adjustment invoice's ID and its own payment reference do not change; completion finds the sequence from either form.
- **A charge whose plan did not change is due for a refund.** `completeAfterCharge` changes the plan first. If that returns false it issues no document, closes the intent with `closed_reason = REFUND_DUE` and logs an error.

## Consequences

- A declined upgrade can be retried in the same period, and is charged.
- Two concurrent upgrades produce one charge.
- A refund is only flagged: there is no refund flow and no alert on `REFUND_DUE` yet. An operator finds them with `SELECT * FROM invoicing.upgrade_intent WHERE closed_reason = 'REFUND_DUE'`. A refund flow and an alert are follow-ups.
- During a rolling deploy the previous release still treats a closed intent as open (it does not read `closed_at`); a decline in that window is retried only once the new release serves the request.

## Rejected

- Retrying a declined upgrade with Payment's `collectAgain` on the same reference: that charges the first request's amount, not a fresh quote, and it is Collections' tool for invoices.
- `ON CONFLICT DO NOTHING` without naming the guard: it hides every unique violation (CLAUDE.md rule 11).
