# ADR-043. The billing day belongs to the account, not to each subscription

**Status:** Accepted, 24 November 2026 (WAR-91).

## Context

The Design Record puts the billing day on Account: "The billing day is set by the first subscription." Periods follow that day (S5). Subscription asks Account for it.

The code has no billing day on Account. Each subscription calculates its own anchor day from its own start date (`Subscription.start`, `BillingCalendar`).

An account may have only one live subscription today (A-14, a scope limit). Both models then give the same dates, so the difference is invisible. After WAR-64 (several subscriptions per account) it shows. Example: subscription 1 starts on the 5th; subscription 2 starts on the 20th.

- Design Record: both bill on the 5th. The second subscription needs a short first period, with proration.
- Code: one bills on the 5th, the other on the 20th. The account gets two invoices and two charges each month.

Kill Bill, used as a check in the Design Record, puts the bill cycle day on the account and does not let it change after it is set (`DefaultAccount.validateAccountUpdateInput`, read on 15 October 2026).

Other design choices already assume one billing day per account. Design Record §12 rejected one statement aggregate per account because every subscription on an account would renew on the same billing day (S5), so invoices would collide at peak; that is why each invoice is its own aggregate. `design.md` and the glossary already state S5 as an account billing day. The code drifted; this record chooses whether to bring the code back, or to supersede S5.

This record is decided before WAR-64 starts.

## Options

- **A.** Follow the Design Record: one billing day for each account.
- **B.** Follow the code: one anchor day for each subscription. Then supersede S5 and Account rule 2 (the Design Record snapshot would not be edited).

## Decision

We will follow the Design Record (option A). The billing day belongs to the account. It is set by the first subscription, from that subscription's start date in the account's time zone, and then does not change, even if every subscription later ends and the account signs up again.

A later subscription that starts on the billing day has a normal first period. A later subscription that starts on another day has a short first period, from its start date to the next billing day, priced by days (O4, ADR-017). That first invoice is a period invoice for period 1, not an adjustment: this is a start, not a mid-period change of an existing period. Sign-up access before the first payment (ADR-014) is unchanged.

Each subscription may still store a copy of the account's billing day as its anchor, because the day does not change. The account remains the source of truth.

Two subscriptions on the same account still produce two period invoices (I2 is per subscription and period). They will often be issued on the same billing day; that collision was already anticipated in Design Record §12.

The Design Record snapshot is not edited. S5, Account rule 2, `design.md` and the glossary already state this choice.

Option B is rejected, not deferred. A later change to a per-subscription billing day would have to supersede this record and S5, not quietly widen `Subscription.start`.

## Consequences

- Account gains a billing day, set once, on the first subscription's start. Existing accounts are backfilled from their earliest subscription's anchor day, or left unset until their first subscription starts.
- `Subscription.start` asks Account for the billing day. If it is not yet set, this start sets it.
- WAR-64 can lift the one-live-subscription limit on this model. It still needs a sign-up idempotency guard that does not rely on that unique index.
- The household has one billing day and, typically, one charge window, even when it holds several subscriptions.

## Compliance

When WAR-64 is built, not before:

- Two live subscriptions on one account share the account's billing day. The one that did not start on that day has a short, prorated first period ending on the next billing day.
- The billing day, once set, does not change when a later subscription starts or when every subscription has ended.

## Drivers

S5; Account rule 2; D7; Design Record §6 and §12; WAR-91; WAR-64.

## Revisit when

A product decision is taken to let each subscription keep its own billing day, or to let the household change the account's billing day after it is set, which would supersede this record.

## Notes

Accepted 24 November 2026, decided by delegation (Waleed, 24 November 2026: "Feel free to take decisions and just let me know"). A short row for 043 is in the decision table in [`README.md`](README.md).
