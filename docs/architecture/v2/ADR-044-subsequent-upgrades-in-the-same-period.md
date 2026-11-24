# ADR-044. A later upgrade in the same period uses the next adjustment and credits the plan being left

**Status:** Accepted, 24 November 2026 (WAR-62 review; unblocks a second upgrade in one period).

## Context

ADR-040 gives each mid-period change a credit note plus an adjustment invoice. The first adjustment of a period is numbered 1; its ID is a function of the period invoice and that sequence. ADR-042 says the credit note of an upgrade sits against the paid period invoice.

The first implementation always used sequence 1 and looked up the intent by that first adjustment's reference. A second upgrade in the same period therefore found the completed first intent and returned without doing anything.

The period invoice also cannot absorb every later credit. After the first upgrade, some of its amount is already credited (the unused days of the plan the household left). A later upgrade credits unused days of the plan the household is on now, which is the previous adjustment, and that credit can be larger than what remains on the original period invoice.

## Decision

A later upgrade in the same period uses the next adjustment sequence for that period invoice. The charge reference is that adjustment's reference, so a retry of the same upgrade still charges once (D1).

The credit note of an upgrade corrects the invoice that billed the plan being left: the period invoice on the first upgrade of the period (ADR-042), and the previous adjustment on a later upgrade in the same period. The credit is still applied to the new adjustment, as V48 already does. ADR-042 is not edited; it remains the rule for the first upgrade of the period.

A second upgrade is refused while an earlier upgrade of the same period invoice is still in flight (an intent exists and is not completed). A retry of that same upgrade, with the same new plan, still charges the same reference.

The Design Record snapshot is not edited.

## Consequences

- Upgrade looks at the adjustments already issued for the period invoice, and at any open intent, to choose the sequence and the invoice to credit.
- Two successful upgrades in one period produce two adjustments and two credit notes. The second credit sits against the first adjustment, not the period invoice.
- A failed first charge still leaves no documents (ADR-041); a later attempt is a retry of sequence 1, not a new sequence.

## Compliance

- A second successful upgrade in the same period issues adjustment 2 and a credit note against adjustment 1, and moves the subscription to the newer plan.
- A second upgrade requested while the first intent is still open, for a different plan, is refused, and no new charge is started.

## Drivers

ADR-040; ADR-041; ADR-042; D1; I1.

## Revisit when

Finance wants later upgrades in the same period to credit the original period invoice, or to replace earlier adjustments in place, which would supersede this record.

## Notes

Accepted 24 November 2026, decided by delegation (Waleed, 24 November 2026: "Feel free to take decisions and just let me know"). A short row for 044 is in the decision table in [`README.md`](README.md).
