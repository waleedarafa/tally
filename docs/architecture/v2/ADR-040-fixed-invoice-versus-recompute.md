# ADR-040. One fixed invoice per period; mid-period changes use credit notes and adjustment invoices

**Status:** Accepted, 24 November 2026 (WAR-61).

## Context

A household upgrades on the 15th. Someone has to work out the difference for the rest of the month. WAR-62 will build plan changes with proration, and is blocked on this record and on the subscription state machine (WAR-60).

Tally already issues one invoice per subscription per period. An issued invoice never changes; corrections are credit notes (I1, G7). The invoice's ID is the subscription and the period number (I2). Invoice lines come from a PriceQuote (I3). The Design Record's Kill Bill check after Slice 1R records the other option and says it was never compared:

> One real difference: Kill Bill keeps subscriptions as an event log and recomputes and diffs invoices on every run, while Tally issues one fixed invoice per period. Ours keeps "an issued invoice never changes" (I1) trivially true; Kill Bill's handles mid-period changes without credit-note logic. We chose without comparing. The slice that builds upgrades and proration compares the two options before it starts.

WAR-61's output is this comparison, scored against D1, D5 and D7.

The design already describes mid-period upgrades under the fixed-invoice model: proration is by days (O4); an upgrade is charged at once on an adjustment invoice (H5); a credit is a negative PriceLine; CreditNote is an Invoicing aggregate, and `CreditNoteIssued` is a listed event. Step 3's worked example is Basic 12 to Premium 20 on day 16 of 30: credit 6.00, charge 10.00, net 4.00 + 0.20 VAT. None of that is in the code yet: there is no `CreditNote` type, no `issue adjustment` command, no proration rule, and `InvoicingListeners` bills only `STARTED` and `PERIOD_RENEWED`. `SubscriptionApi` has no `changePlan`. `Pricing.quote` prices a whole period. A negative invoice total is refused, in code and by `amount_minor >= 0`, because "owing the customer money is a credit note's job (I1)".

I4 and I5 are named once in the Design Record ("Invoice enforces I1 to I5") and are not defined in `design.md` or the Invoicing canvas. This record does not invent them.

## Options

### A. One fixed invoice per period (as designed)

The period invoice is issued once, when the period starts or is renewed, from `Pricing.quote` for that period. Its ID stays `UUID.nameUUIDFromBytes("invoice:" + subscriptionId + ":" + periodNumber)`, which is also the unique constraint `invoice_one_per_subscription_and_period`. Its lines, amount, offer version and `quote_reason` are written at issue and are not updated. Payment status may still move (`PAYMENT_PENDING` to `PAID` or `PAYMENT_FAILED`): that is not a correction of the legal amounts.

A mid-period change does not edit that invoice. Offers and Pricing answers what the change costs (the inbound query already on the Offers canvas; O4, R2, I3). Invoicing records the result as:

- a credit note against the period invoice, for unused days at the old price, and
- an adjustment invoice, which charges the remaining days at the new price (H5).

The net of those two documents is what is collected now. The next period-end renewal is unchanged: `Renewals` still enqueues one item, keyed by `accountId:subscriptionId:periodEnd` (D-26), due at local midnight of the next period's first day; `renew(n)` plus `addIfAbsent` on the period invoice still makes a repeat harmless (D1, ADR-003).

A customer cancel still takes effect at period end, with no refund for the current period (S4, H11): no credit note for that cancel. A downgrade still takes effect at period end (S3, H6): no mid-period invoice. Two cases already designed to use a credit note stay as they are: an invoice issued in a race while the account is blocked (I6), and "renewal reversed" when a cancel requested before period end is processed after the renewal (Design Record §12).

**Amendment to I2.** I2 as previously written, and the unique constraint, meant a second invoice could not use the same subscription and period number. H5's adjustment invoice therefore cannot share the period invoice's ID. This record amends I2: there is still at most one *period invoice* per subscription per period, and its ID is still subscription + period number; an adjustment invoice is a different document, with its own ID, tied to that period and to the period invoice. A credit note likewise refers to the invoice it corrects, and the total credited never exceeds that invoice (already assumed on the CreditNote aggregate). The daily overlapping-period check (`invoicing.duplicate-invoices`) would need to ignore, or otherwise not treat as a duplicate, an adjustment that shares the period's dates.

The VAT point remains the invoice date (H8), so the adjustment invoice has its own VAT point on the day of the change. Schema changes are additive (ADR-009): a credit-note table, and a way to tell an adjustment invoice from a period invoice.

### B. Recompute-and-diff, as the Design Record describes Kill Bill

Subscriptions are kept as an event log. On every billing run the engine recomputes what the account owes and diffs that against what has already been invoiced. A mid-period upgrade is just another event: the difference falls out of the run, without a separate credit-note path. WAR-61 and the Slice 1R Kill Bill check give this as the cost: a more complex invoice engine.

The documents do not describe how that engine would issue, store, or identify the diff, nor how two copies of the billing run (D1's environment) would claim a recompute. They do say that this model does not keep I1 "trivially true". Adopting it would have to supersede, or sharply rewrite, I1, and would have to replace or bypass I2's "one invoice, whose ID is the subscription and period". The existing issuer, the unique constraint, the refusal of a negative invoice, and the stored `quote_reason` would all have to change. The renewal queue could still create the next period; mid-period changes would also have to trigger a recompute, which the current queue does not do.

This record does not add Kill Bill behaviour beyond what the Design Record and WAR-61 already state.

## Decision

We will keep one fixed invoice per period. Mid-period changes (WAR-62) are recorded as a credit note plus an adjustment invoice, with the I2 amendment above. We will not adopt a recompute-and-diff invoice engine.

Business justification: I1 is already how Tally meets G7 (an issued invoice is a legal record that never changes). D7 already stores the PriceQuote reason on the invoice, once, and never updates it. D1's "one invoice is issued" is already implemented for renewals by a deterministic ID and `addIfAbsent`. The design already has credit notes, adjustment invoices, and proration-by-days in Offers and Pricing; the missing work is to build them, not to replace the invoice model.

## Scoring against D1, D5 and D7

From [`drivers.md`](drivers.md).

| Driver | Option A, fixed invoice | Option B, recompute-and-diff |
|---|---|---|
| **D1.** A renewal happens exactly once. Two copies of the billing run are active; one crashes halfway. The period renews once, and one invoice is issued. | **Favours A.** The period invoice stays unique by construction (I2, `addIfAbsent`, `renew(n)`, the period unique on `subscription.subscription_period`). A mid-period change is not a renewal and does not enqueue one. The extra documents are a credit note and an adjustment invoice, each issued once from a named command. | **Weaker.** A later run that recomputes the same period is a second writer of "what is owed" for a period whose invoice already exists. The documents do not say how that run stays unique when two copies claim work (assumption: this would need a new uniqueness story, not the current invoice ID). D1's measure, "one invoice is issued", is no longer the shape of the model. |
| **D5.** A new kind of offer ships, changing only Offers and Pricing, behind a flag, in 2 weeks. | **Favours A, with one assumption.** Proration and "what does this change cost?" already belong to Offers and Pricing (O4, R2, ADR-015). Invoicing only records amounts as credit notes and invoices (I3). Assumption: a new kind of offer can express a mid-period change as PriceQuote lines, including negative credits. If it could not, Invoicing would have to learn offer policy, and D5 would fail. The credit-note and adjustment path is built once in WAR-62; it should not grow for each new kind. | **Mixed, and the risk sits in the wrong context.** If the engine only diffs quotes, a new kind could still ship in Offers and Pricing (assumption). If mid-period behaviour lives in the invoice engine, a new kind changes Invoicing, which is a generic context, and D5 is missed. The Design Record already calls that engine more complex. |
| **D7.** An auditor asks why a charge happened, and traces it to PriceQuote reason, offer version and period within one working day. | **Favours A.** The period invoice already stores lines, offer version and `quote_reason` at issue, and never updates the reason. A mid-period charge is a second immutable document with its own reason and VAT point (H8). The auditor reads the period invoice, the credit notes against it, and the adjustment invoices for that period. | **Weaker unless every diff is itself an immutable document with a reason.** Recomputing an issued invoice would change a record I1 says never changes, and would blur which reason applied on which day. The Design Record's contrast is that I1 stays trivially true only under option A. |

## Consequences

- I1 stands. WAR-62 does not edit issued period invoices.
- I2 is amended as proposed above. The unique constraint `invoice_one_per_subscription_and_period` keeps guarding the period invoice. Adjustment invoices need a different identity, added with an expand-and-contract migration (ADR-009).
- CreditNote is built in Invoicing, with `CreditNoteIssued`. A credit note refers to one invoice; the total credited never exceeds that invoice (the aggregate's assumed rule).
- `Invoice.issue` keeps refusing a negative total. A net credit is a credit note, not a negative invoice.
- `InvoicingListeners` will also react to a plan-changed event, once Subscription publishes one. It still issues the period invoice only on `STARTED` and `PERIOD_RENEWED`.
- Offers and Pricing gains the "what does this change cost?" path and a proration rule by days (O4). Rounding of a prorated line stays as ADR-017 (half up on the prorated line and on VAT per line; totals are sums).
- The renewal queue does not change shape: one item per subscription and next period, still not a mid-period trigger.
- The daily check `invoicing.duplicate-invoices` must be revised so an adjustment for the current period is not counted as a duplicate period invoice. The check for `amount_minor < 0` stays on invoices.
- Option B is rejected, not deferred. A later change to recompute-and-diff would have to supersede this record and I1, not quietly widen InvoiceIssuer.

## What this unblocks in WAR-62

WAR-62 can design the upgrade saga on this model: change the subscription, ask Offers for the proration quote, issue the credit note and the adjustment invoice, charge the net, grant the new access. The charge remains the saga's pivot, as WAR-62 already says.

WAR-62 still has its own product decision, not settled here: Design Record §3 and the upgrade story give the new plan's access at once, before payment; the course review recommended unlocking only after the charge succeeds (topic D-09). That decision is independent of how the difference is invoiced.

## Compliance

When WAR-62 is built, not before:

- An issued period invoice's lines, amount, offer version and `quote_reason` do not change after insert.
- A mid-period upgrade leaves that invoice in place, writes one credit note and one adjustment invoice, and the net of those two equals the proration quote (the Step 3 numbers are the first golden case: credit 6.00, charge 10.00, net 4.00 before VAT).
- A second period invoice for the same subscription and period number is still impossible.
- A renewal still issues exactly one period invoice, including when two copies run (AT-9).
- A cancel at period end issues no credit note for the current period.
- D7: the adjustment invoice and the credit note each carry a PriceQuote reason that traces to offer version and period.

## Drivers

D1, D5, D7. Also G7 (I1), G4 (never lose or double revenue), O4, R2, I1–I3, H5, H8, ADR-009, ADR-017.

## Revisit when

- Finance wants one invoice per account per billing run. The Design Record already says that reopens the Invoice size decision; it is a different question from this one.
- A new kind of offer cannot express a mid-period change as PriceQuote lines (the D5 assumption above fails).
- The assumption that a bought invoicing product could replace Invoicing (Design Record, Invoicing canvas) is taken up, and that product chooses a recompute model.

## Open questions

1. Adjustment invoice identity: the exact ID scheme (for example a name-based UUID that includes a sequence or a reason, not only subscription and period) and the column that ties it to the period invoice. The amendment above stands; the concrete schema is WAR-62's.
2. Whether an unpaid period invoice, mid-upgrade, is credited in full and replaced by the adjustment, or is left owed and the credit note sits against it until both are settled. The documents do not say. This affects Collections, which already covers every unpaid invoice of the account (ADR-037).
3. I4 and I5 are referenced and never defined. They should be found or dropped, separately, and not filled in here.
4. WAR-91 (does the billing day belong to the account or to each subscription?) is a different decision. It does not change this comparison; it may change how period dates are calculated.
5. Tax on the credit note and the adjustment invoice (rates, whether the 0.20 VAT in the Step 3 example is illustrative only). H8 says the VAT point is the invoice date; the `TaxRates` port is WAR-67. This record does not choose tax behaviour.

## Notes

Accepted 24 November 2026 (WAR-61). Sources: WAR-61; Design Record Slice 1R Kill Bill check and the Invoicing, Offers, Subscription and Aggregates canvases; `design.md` §2 and §4; `drivers.md`; `glossary.md`; `README.md` decisions 003, 009, 015, 017; topic D-09; the current `Invoice`, `InvoiceIssuer`, `InvoicingListeners`, `Renewals` and `Pricing` code, and migrations V7 and V20.

A short row for 040 is in the decision table in [`README.md`](README.md). I2 is amended in `design.md` §2. First-run FR-040 is a different, archived record (the approved dependency graph).
