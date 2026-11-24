# ADR-041. On an upgrade, the new plan's access is granted only after the charge succeeds

**Status:** Accepted, 24 November 2026 (WAR-62, topic D-09).

## Context

A mid-period upgrade is a saga: change the subscription, price the difference, charge it, grant the new access. The charge is the pivot: after it, the saga can only go forward (WAR-62). ADR-040 decides how the difference is invoiced (a credit note plus an adjustment invoice) and leaves this product decision open.

Design Record §3's upgrade story grants access at once, after "Plan changed" and before "Payment requested": "Access upgraded at once". Slice 1R also gives access at sign-up, before the first charge. The course review recommended unlocking the new plan only after the charge succeeds. Topic D-09 recorded that conflict and asked for a decision, not a status. Sign-up access (ADR-014) and a decline on the first invoice (ADR-025) are different cases; this record is only about a mid-period upgrade.

S3 ("Upgrade at once; downgrade at period end") is the plan's effective time, from H6: an upgrade takes effect in the current period, a downgrade at period end. It is not a decision about when Access changes.

## Decision

We will grant the new plan's access only after the charge for the upgrade succeeds. Until then the household keeps the old plan's access. After the charge succeeds, the saga only goes forward (WAR-62).

The credit note and the adjustment invoice are issued only after that charge succeeds, not before it. The amount to charge is taken from the proration quote, before any of those documents is issued. A failed charge leaves no credit note, no adjustment invoice, and the old plan in place.

This is the course review's recommendation for D-09. It supersedes Design Record §3 and the upgrade story's "Access upgraded at once" for mid-period upgrades. The Design Record snapshot is not edited.

The invoicing model stays ADR-040. The charge remains the saga's pivot (WAR-62; Design Record, decision 005; topic D2-02). Because the documents are not issued until after the charge succeeds, a failed charge needs no compensation, and I1 holds trivially: no issued invoice is edited.

## Consequences

- Access does not change when the upgrade is requested. It changes when the upgrade charge succeeds, and that is also when the credit note and the adjustment invoice are issued.
- A failed charge (declined, or otherwise not succeeded) does not grant the new plan. The household keeps the old plan's access. No credit note and no adjustment invoice are issued, so nothing needs compensating.
- ADR-040's open question 2 (whether an unpaid period invoice, mid-upgrade, is credited in full and replaced by the adjustment, or is left owed with the credit note against it) is not settled here, and ADR-040 is not edited. That question arises only after a successful charge, once those documents are issued. A failed charge never issues them, so it does not meet question 2.
- S3 is unchanged: the upgrade is still a mid-period plan change, not one delayed to period end.
- Sign-up access before the first payment (ADR-014, Slice 1R) and ending the subscription on a declined first invoice (ADR-025) are unchanged.

## Compliance

When WAR-62 is built, not before:

- An upgrade whose charge has not succeeded leaves Access answering for the old plan.
- After the charge succeeds, Access answers for the new plan, and the credit note and adjustment invoice have been issued.
- A declined or failed upgrade charge does not grant the new plan, and issues no credit note and no adjustment invoice.

## Drivers

Topic D-09; WAR-62; ADR-040; decision 005 (charge as pivot).

## Revisit when

A product decision is taken to grant upgrade access before payment, which would supersede this record and return to the Design Record §3 story.

## Notes

Accepted 24 November 2026, from Waleed's decisions on D-09 and on waiting to issue the credit note and adjustment invoice until the upgrade charge succeeds. A short row for 041 is in the decision table in [`README.md`](README.md). S3 in [`design.md`](design.md) is unchanged; a sentence there points here for access. Topic D-09 in the topic tracker is updated to Designed. ADR-040 is not edited.
