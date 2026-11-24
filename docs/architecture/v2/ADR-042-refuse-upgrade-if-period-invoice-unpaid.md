# ADR-042. An upgrade is refused while the current period invoice is unpaid

**Status:** Accepted, 24 November 2026 (WAR-62). Resolves ADR-040 open question 2.

## Context

ADR-040 left open how an unpaid period invoice is settled mid-upgrade: whether it is credited in full and replaced by the adjustment, or left owed with the credit note against it. That question arises only after a successful upgrade charge, once the credit note and adjustment invoice are issued (ADR-041).

Collections already covers every unpaid invoice of the account (ADR-037). An upgrade that proceeds while the period invoice is unpaid would put a credit note against a debt still in Collections, and would mix a plan change with recovery of an unpaid period.

## Decision

We will refuse an upgrade while the current period invoice is unpaid. The household pays or follows Collections first, then upgrades. The credit note therefore always sits against a paid period invoice.

"Unpaid" here means the period invoice is not `PAID`. A zero invoice, which is paid when issued, does not block an upgrade.

This record does not change ADR-040's invoice model or ADR-041's rule that access and the credit note and adjustment invoice wait until the upgrade charge succeeds. The Design Record snapshot is not edited, and ADR-040's text is not edited.

## Consequences

- An upgrade is attempted only when the current period invoice is `PAID`.
- If it is `PAYMENT_PENDING` or `PAYMENT_FAILED`, the upgrade is refused and no proration charge, credit note, adjustment invoice, plan change or access change is made.
- The API should point the household to paying the open invoice (Collections' "pay now" already exists). The exact problem-details type and status are left to WAR-62, following RFC 9457 (ADR-019).
- ADR-040 open question 2 is settled by refusal, not by a mid-upgrade settlement path.

## Compliance

When WAR-62 is built, not before:

- An upgrade while the current period invoice is unpaid is refused, and the subscription, invoices, credit notes and access facts are unchanged.
- An upgrade while the current period invoice is paid may proceed, subject to ADR-041.

## Drivers

ADR-040 open question 2; ADR-041; ADR-037; I1.

## Revisit when

Finance wants upgrades to proceed against an unpaid period invoice, which would supersede this record and reopen how that invoice is settled.

## Notes

Accepted 24 November 2026, from Waleed's confirmation of the recommendation on ADR-040 open question 2. A short row for 042 is in the decision table in [`README.md`](README.md).
