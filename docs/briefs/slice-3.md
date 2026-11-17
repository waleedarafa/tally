# Slice 3 brief: Collections

Status of this brief: **confirmed**. The user confirmed the four decisions below on 15 November 2026, on pull request 74: option A for each.

## Goal

Today a household whose card is declined keeps watching for free.

Example: the March renewal of a household is declined. Collections opens a case. On day 3 it asks for the payment again. The card is declined again. After that, nothing happens. No timer exists for day 7, day 10 or day 30. The standing stays GOOD, and Access says yes for ever.

The Design Record gives the rule (§7 "Collections", rule 1; H4, H7): retry on day 3 and day 7, overdue on day 7, block on day 10, write off on day 30.

When this slice is done:

- A household that does not pay is OVERDUE after the second retry, BLOCKED on day 10, and written off on day 30.
- A blocked household cannot watch. Access says no, with reason `ACCOUNT_BLOCKED`.
- A household that pays, at any step, can watch again.
- A write-off ends the subscription. No renewal follows.
- A test runs the 30 days in under 1 minute (D10).

Slice 2 built the path from the standing to Access (`StandingChanged` → `accessfeed` → the log → Access). Nothing calls `AccountStanding.set` yet. This slice makes the first caller.

## Why this slice comes next

- It gives the work of Slice 2 a business use. The standing reaches Access, but nothing changes it.
- It closes a hole on `main`: after day 3, an unpaid household watches for free with no limit.
- It needs no change to the module graph: `collections` may already use `account`, `subscription` and `invoicing`.
- It is smaller than the billing lifecycle slice, and that slice still waits for two decisions (WAR-61, WAR-91).
- Authentication (WAR-49) is required before production, not before this. It changes every endpoint, so it is better alone.

## Read first

1. `docs/architecture/v2/README.md`: decisions 005, 009, 013, 016, 019, 026, 028, 030, 033, and proposed 025.
2. `docs/architecture/v2/design-record.md`: §7 "Collections" (rules 1 to 4), hot spots H4, H7 and H11, the context map rows for Collections, R3, and "Inputs to later slices".
3. `docs/architecture/v2/design.md` §2 (I6), §3, §4 and §5 (the Subscription and Collections state machines).
4. `docs/briefs/slice-2-report.md` §4.4, limit 4 (a `set` inside a sign-up's window).
5. killbill-wiki: the overdue module. Use it to compare, not to copy.

## What exists today (checked against `main` at 1b18edc)

- `CollectionsService.on(InvoicePaymentDeclined)` opens one case per account with an atomic insert, and puts the day-3 retry on `collections.retry_queue`.
- A case holds **one** invoice. A second decline while a case is open only writes a log line. Nothing retries that second invoice.
- `CaseStatus` has OPEN and CLOSED. `InvoicePaid` closes the case.
- `AccountStanding.set(accountId, standing, cause)` exists. F3 allows only `collections` to call it. Nothing calls it.
- `Standing` has GOOD, OVERDUE and BLOCKED. Sign-up refuses a BLOCKED account (`AccountBlockedException`).
- A subscription cannot end. `SubscriptionChangeType` has STARTED and PERIOD_RENEWED. `subscription_one_per_account` is `UNIQUE (account_id)`.
- `Renewals.handle` renews without a look at the standing.
- No endpoint lets a household pay an unpaid invoice. After the last retry, only time passes.
- AT-16 declines the **first** invoice of a new subscription, then pays on day 3.

## Decisions, confirmed by the user on 15 November 2026

Finance and product own these four. The user chose option A for each. The other options stay here with their costs, as the record of what was compared.

Two more were confirmed on #75, after spike S-1 (`docs/briefs/slice-3-spikes.md`): the case becomes OVERDUE by a timer one hour after retry 2 is asked, if a debt is still open (no event tells Collections that a retry was declined); and Collections finds the first invoice through `InvoicingApi`.

### 1. A decline on the first invoice (proposed 025)

A new subscriber gets access at sign-up, before the first charge. Then the first charge is declined.

| Option | What happens | Cost |
|---|---|---|
| **A. End the subscription at once (chosen)** | No case. Access says no. The household signs up again with a card that works. | A household whose card had a short problem must sign up again. |
| B. The normal 30-day timetable | The household watches until day 10. | Anyone with a bad card watches 10 days for free, again and again. |
| C. A short grace: one retry after 24 hours, then end | One more chance. | One more timer and one more state, for a person who is still at the screen. |

### 2. How a household pays after the last retry

The timetable has two retries. After day 7 nothing asks for the payment again. So OVERDUE → GOOD and BLOCKED → GOOD can never happen.

| Option | What happens | Cost |
|---|---|---|
| **A. "Pay now" (chosen)** | The household asks Tally to try the payment again, at any time while a debt is open. | One new endpoint, with an idempotency key. |
| B. More automatic retries (day 10, day 30) | Tally tries again before it blocks and before it writes off. | A change to finance's timetable (H4). A household that repairs its card on day 12 waits until day 30. |
| C. Both | | Both costs. |

"Pay now" uses the card that the account already has. A change of card is out of scope (WAR-86).

### 3. The renewal date of a blocked account

A household is blocked on 10 March. Its next renewal date is 1 April.

| Option | What happens | Cost |
|---|---|---|
| **A. Postpone the renewal while BLOCKED (chosen)** | No new period and no new invoice while blocked (I6). The renewal runs when the standing is GOOD again. | The period keeps its dates. A household restored on 5 April pays for all of April. |
| B. Renew and invoice as usual | The new invoice is declined and joins the case. | The household gets a debt for a month in which it could not watch. This breaks I6. |

Proration would remove the cost of option A. It belongs to the billing lifecycle slice (WAR-62).

### 4. The standing after a write-off

| Option | What happens | Cost |
|---|---|---|
| **A. The standing stays BLOCKED (chosen)** | The household pays the debt with "pay now". Then the standing is GOOD, and it can sign up again (H11). | A household that never pays can never come back on this account. |
| B. The standing returns to GOOD at the write-off | The household can sign up again at once. | It can repeat this: 10 free days for each sign-up. |

## Rules of the design (not open)

These follow from accepted records. They are not choices of this slice.

1. **The timetable** counts days from the first decline of the case. Day 3: retry 1. Day 7: retry 2. Day 7 plus one hour: OVERDUE, if a debt is still open. Day 10: BLOCKED, if a debt is still open. Day 30: WRITTEN_OFF, if a debt is still open.
2. **Only a decline opens a case** (rule 9, ADR-013). After that, no payment outcome moves the case forward: each timer looks only at "is a debt still open". Only `InvoicePaid` moves it back. The one hour is D2's measure: an unknown payment is resolved within one hour.
3. **Collections never moves money** (Design Record §7, rule 2). It asks Invoicing to request the payment again.
4. **Only Collections sets the standing** (R3, F3).
5. **The standing follows the case in one transaction.** The step that changes the case's state calls `AccountStanding.set` in the same transaction. The case's version orders a race. Example: `InvoicePaid` and the day-10 timer arrive at the same time. One wins the lock; the other reads the new state and acts on it. The end state is never "case closed, standing BLOCKED".
6. **Each timer is a claimed row** on Collections' queue, with a business unique constraint for the step (rule 5, rule 11).
7. **One case covers every unpaid invoice of the account** (Design Record, "Inputs to later slices"; WAR-58). A decline while a case is open adds the invoice to the case. A retry and "pay now" ask for every unpaid invoice of the case. The case closes only when none is unpaid.
8. **`ENDED` refuses everything** (design.md §5). An ended subscription is never renewed.
9. **Events and the contract are add-only** (rule 7, ADR-016).

## Target shape

```
payment declined (a renewal)
   │
   ▼
 OPEN ──day 3: retry 1──► OPEN ──day 7 + 1 h: a debt is still open──► OVERDUE ──day 10──► BLOCKED ──day 30──► WRITTEN_OFF
   │                        │                                  │                   │                    │
   └────────────── every unpaid invoice is paid (a retry or "pay now") ────────────┘                    │
                                      │                                                                 │
                                      ▼                                                                 ▼
                              CLOSED, standing GOOD                               subscription ENDED, standing BLOCKED
                                                                                  paid later: standing GOOD, still ENDED

standing:   GOOD ............................ OVERDUE ........... BLOCKED .......... BLOCKED
Access:     yes .............................. yes ............... no ............... no
```

Three modules change. Access does not change.

| Module | Change |
|---|---|
| `collections` | The case covers many invoices. The states OVERDUE, BLOCKED and WRITTEN_OFF. The timers. The calls to `AccountStanding.set` and to Subscription. The "pay now" endpoint. |
| `subscription` | A subscription can end. `SubscriptionChanged` of type `ENDED`. The renewal waits while the account is BLOCKED. One **live** subscription per account. |
| `accessfeed` | On `ENDED`, the source leaves the account's facts, and the sequence goes up. |
| `account` | A `set` that meets a sign-up never gives the sign-up a 500 (WAR-57). |
| Access (`access/`) | **None.** `ACCOUNT_BLOCKED` and `NO_ACTIVE_SOURCE` exist. If this slice needs a change in `access/src/main`, stop and ask. |

That last row is the test of the Slice 2 boundary: a new billing rule ships with no Access release.

## The subscription can end (the smallest part of design.md §5)

- New public command on `SubscriptionApi`: end the account's live subscriptions, with a reason (`WRITE_OFF` or `FIRST_PAYMENT_DECLINED`). `collections` is the only caller in this slice.
- New change type `ENDED` (add only). A consumer that does not know it ignores it and counts it, as today.
- The transition: any live state → ENDED. ENDED refuses `renew`. The pending renewal row is harmless: the handler finds ENDED and does nothing.
- `subscription_one_per_account` becomes "one live subscription per account" (a partial unique index). An account with an ended subscription can sign up again. Sign-up's duplicate guard names the new constraint (rule 11).
- `accessfeed`: on `ENDED`, remove the source. The message then has an empty `sources` list. The schema allows that today, and Access answers `NO_ACTIVE_SOURCE`. Add an example file for it.

Not in this slice: TRIAL, CANCELLING, a cancel by the customer, "renewal reversed", refunds.

## "Pay now"

`POST /v1/accounts/{accountId}/collections/payment-requests`

- Needs an `Idempotency-Key` (rule 13), with a key table for `collections`.
- 202: Collections asked Invoicing to request the payment again for every unpaid invoice of the case. The outcome arrives later, as `InvoicePaid` or a decline.
- 404: the account has no open debt.
- It works in the states OPEN, OVERDUE, BLOCKED and WRITTEN_OFF.
- Every request of a case, scheduled or "pay now", takes the next retry number at the moment it is asked. The permanent guard is the case's retry row (rule 11). *(Changed on #80, option B, confirmed 16 November 2026: before, an early declined "pay now" took a number above the timetable's and Payment then skipped the day-3 and day-7 retries.)*
- No transaction is open during the call to the provider (rule 6).
- A decline of a "pay now" request changes no state. The timers decide.

## In scope

- Everything in "Target shape".
- The four decisions, as confirmed.
- WAR-57's second part: a `set` and a sign-up for one account at the same time. The sign-up answers 409 with `Retry-After`; it never answers 500. The `set` runs on the queue, so it tries again.
- Health for every new timer (rule 8), and alert rules with runbooks for work that is late (ADR-030).
- No change in `invoicing` or `payment` for the OVERDUE step: it is a timer (rule 1).
- The documents: `design.md` §4, §5 and §8, the glossary, the topic tracker (C-06, D-07, D-09, D-10), `docs/api/openapi.yaml`.

## Out of scope

- Messages to the customer (email on decline, on block). No email service exists.
- A change of card, and card setup with a real provider (WAR-86).
- Invoice states for a write-off or a void, credit notes, the ledger. An invoice of a written-off or ended subscription stays `PAYMENT_FAILED`. Say so in the report.
- Proration and a moved billing day (WAR-62, WAR-91).
- Trials, a cancel by the customer, plan changes (WAR-60, WAR-62).
- Several subscriptions per account (WAR-64).
- The support view.
- Authentication (WAR-49). "Pay now" is as open as sign-up is today.

## Spikes, before PR-2

Write the findings in `docs/briefs/slice-3-spikes.md`, each with a test that pins it.

| Spike | Question |
|---|---|
| S-1 | How does Collections know that a declined invoice is the first invoice of a subscription, and that a decline belongs to retry 2? Is the data in `InvoicePaymentDeclined`, or behind `InvoicingApi`? If an event field must be added, it is additive (rule 7). |
| S-2 | Kill Bill check, with path:line citations. What does Kill Bill's overdue module do to billing while an account is blocked? What happens to the period when the block ends? Compare with decision 3. Mark anything not read in the code as "inference". |
| S-3 | D10: can one acceptance test move the clock through 30 days, with four timers and a renewal date, in under 1 minute? Measure it on the existing harness. |
| S-4 | A `set` at the same time as a sign-up for one account: show the 500 of today in a test, before the fix. |

## Pull requests, in order

Write each acceptance test before its implementation, and show it red first.

### PR-0 · Spikes

S-1 to S-4, tests only, and the spikes document.

### PR-1 · Docs: adopt this brief (no code)

- `README.md`: 025 with the decided text; new proposed rows from 037 on. Suggested: 037 "the Collections case" (rules 5 and 7 above, "pay now", the standing after a write-off); 038 "a subscription can end; the renewal waits while BLOCKED; one live subscription per account".
- `design.md` §5: the two state machines as this slice builds them.
- CLAUDE.md, "Read first": the current slice is `docs/briefs/slice-3.md`.

### PR-2 · A subscription can end

The section above. Module tests: ENDED refuses a renewal; two "end" commands at the same time end it once; an account with an ended subscription signs up again; the feed removes the source and the sequence goes up; Access's `ExamplesTest` reads the new example. The migration follows ADR-009.

### PR-3 · The case covers every unpaid invoice

A second decline joins the open case. The retry asks for each unpaid invoice. The case closes when none is unpaid. Test: two declines for one account at the same time give one case with two invoices. The migration keeps the open cases of today.

### PR-4 · The timetable and the standing

Day 7 retry, OVERDUE, BLOCKED, WRITTEN_OFF. The standing in the case's transaction. The write-off ends the subscription. The fix for `set` against a sign-up (S-4's test turns green). Tests for each race in rule 5.

AT-22 comes with this pull request.

### PR-5 · "Pay now"

The endpoint, the key table, `openapi.yaml`. AT-23 and AT-25 come with this pull request.

### PR-6 · The renewal waits while BLOCKED

`Renewals.handle` reads the standing. BLOCKED: no renewal now; the item comes back later. Test: the renewal runs within one day after the standing is GOOD again. The invariant check `subscription.next-renewal-scheduled` still holds.

### PR-7 · The first invoice (025)

A decline of the first invoice ends the subscription. No case opens. Collections asks `InvoicingApi` for the invoice and for its subscription's invoices: the first invoice is the earliest period. No new field on `InvoicePaymentDeclined`. AT-24 comes with this pull request. AT-16 changes: its decline moves to a renewal.

### PR-8 · Alerts and runbooks (ADR-030)

One rule for a Collections step that is late, with a firing test, a quiet test and a runbook. F14 must pass. Check that the new metric names do not start with `tally_access_`.

### PR-9 · Report and documents

`docs/briefs/slice-3-report.md` with the six points of CLAUDE.md. The audit covers 005, 013, 016, 025, 028, 033 and the new rows.

## Acceptance tests

| AT | Name | Passes when |
|---|---|---|
| AT-16 | changed | The decline is on a renewal, not on the first invoice. The rest is as today. |
| AT-22 | The household never pays | A renewal is declined. Day 3 and day 7: declined again; standing OVERDUE; Access says yes. Day 10: standing BLOCKED; Access says no, `ACCOUNT_BLOCKED`, with a higher sequence. Day 30: the case is WRITTEN_OFF; the subscription is ENDED; no later renewal makes an invoice. The whole test runs in under 1 minute (D10). |
| AT-23 | The household pays | Four cases. (a) Day 3: the retry succeeds; the case closes; the standing was never changed. (b) Day 8, OVERDUE: "pay now" succeeds; standing GOOD. (c) Day 12, BLOCKED: "pay now" succeeds; standing GOOD; Access says yes again within 5 s. (d) The 1 February renewal is declined, so the 1 March renewal (day 28) waits while BLOCKED; day 29: "pay now" succeeds; standing GOOD; the renewal that waited runs within one day. *(Changed on 17 November 2026, Slice 3 PR-6: on day 12 no renewal date has come, so (c)'s last clause moved to the new case (d).)* |
| AT-24 | The first charge is declined | Sign-up, then the first charge is declined. The subscription is ENDED; no case exists; Access says no, `NO_ACTIVE_SOURCE`. The same account signs up again with a card that works, and Access says yes. |
| AT-25 | Paid after the write-off | Day 35: "pay now" succeeds. Standing GOOD. The subscription stays ENDED. The account signs up again. |

AT-22 to AT-25 ask the Access service in a container, as AT-1 does since Slice 2.

## Stop and ask, instead of deciding, when

- A change is needed in `access/src/main`, or in `contracts/access-facts/v1/schema.json`.
- A module needs a dependency that `architecture/module-dependencies.txt` does not have.
- S-1 shows that Collections cannot tell the first invoice or the retry number without a non-additive change.
- S-3 shows that D10's measure cannot be met.
- A rule in "Rules of the design" conflicts with the code or with an accepted record.
- A migration cannot follow ADR-009 (expand, then contract).

## Evidence used for this brief

- `main` at 1b18edc: `collections/`, `subscription/application/Renewals.java`, `account/AccountStanding.java`, `invoicing/InvoicingApi.java`, `contracts/access-facts/v1/schema.json`, `V5__subscription.sql`, `AT16DeclinedPaymentIT`.
- `docs/architecture/v2/design-record.md`: §7 "Collections", H4, H7, H11, R3, the row of proposed 025, "Inputs to later slices".
- `docs/architecture/v2/design.md` §2 (I6), §5.
- Linear: WAR-57, WAR-58, WAR-59.
- Not verified here: any statement about Kill Bill's overdue module. S-2 verifies it.
- The build was not run for this brief. It changes one document.
