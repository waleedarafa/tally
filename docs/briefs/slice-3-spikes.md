# Slice 3 spikes: findings

Four spikes from `slice-3.md`, before PR-2. Three are tests in `tally/src/test/java/com/tally/spikes/`, run in the acceptance stage; each pins what it found, so a change to it fails the build. S-2 is a reading of Kill Bill's code, with pinned citations.

| Spike | Question | Answer | Pinned by |
|---|---|---|---|
| S-1 | How does Collections know that a declined invoice is the first of its subscription, and that a decline belongs to retry 2? | **The first invoice: yes, through `InvoicingApi`, with no change.** **Retry 2's decline: no.** Today a declined retry publishes no `InvoicePaymentDeclined`, and no event carries a retry number. Two additive fields and one more publication fix it (rule 7); no stop condition is met | `SpikeCollectionsS1DeclineDataIT` (2 tests) |
| S-2 | What does Kill Bill's overdue module do to billing while an account is blocked, and to the period when the block ends? | **No invoice while billing is blocked; the billing day never moves; the blocked days of the period already invoiced are credited back, day by day.** Tally's decision 3 agrees on the first point and differs on the other two | Kill Bill `killbill` at 3868a5b, read in the code (§S-2) |
| S-3 | D10: can one test move the clock through 30 days, with four timers and a renewal date, in under 1 minute? | **Yes: 4.9 s for 32 days**, one day at a time, on the existing harness; 21 s for the whole test class, with its Spring context | `SpikeCollectionsS3ThirtyDaysIT` |
| S-4 | A `set` at the same time as a sign-up for one account: show today's 500 | **500**, `ObjectOptimisticLockingFailureException` on the account's version; nothing of the sign-up is kept | `SpikeCollectionsS4SetDuringSignUpIT` |

## S-1. What a decline tells Collections

### Findings

| # | Finding | Evidence (log prefix `SPIKE-C-S1`) |
|---|---|---|
| 1 | `InvoicePaymentDeclined` carries the account, the invoice, the reference, the amount and the invoice's version. **No subscription, no period, no retry number.** | `[eventId, occurredAt, accountId, invoiceId, paymentReference, amount, sequence]` |
| 2 | `PaymentFailed` carries the reference and the reason. **No attempt or retry number.** | `[eventId, occurredAt, accountId, reference, amount, reason, sequence]` |
| 3 | **A declined retry publishes no second `InvoicePaymentDeclined`.** The day-3 retry was declined, and Payment published a second `PaymentFailed` (`HARD`). But the invoice was already `PAYMENT_FAILED`, so `Invoice.paymentFailed()` returned false (it changes only a `PAYMENT_PENDING` invoice) and `InvoicingListeners` published nothing | `attempts [1=DECLINED, 2=DECLINED]; PaymentFailed 2 ([HARD, HARD]); InvoicePaymentDeclined 1; invoice PAYMENT_FAILED` |
| 4 | **"First invoice" is found through `InvoicingApi` today:** `invoicesFor(account)` gives the declined invoice's subscription, and `invoicesForSubscription(subscription)` shows it is the earliest period. `collections` may already use `invoicing` | `aDeclinedRetryPublishes…FirstInvoiceIsFoundThroughInvoicingApi` |
| 5 | Collections is the only listener for `InvoicePaymentDeclined` | `CollectionsService.on(InvoicePaymentDeclined)`; no other `on(InvoicePaymentDeclined)` in `src/main` |

### What this means for the slice

- **The first invoice (PR-7).** Collections can ask `InvoicingApi`, as finding 4 does. Two cheaper alternatives, both additive: `InvoicePaymentDeclined` gains a nullable `subscriptionId` and `periodNumber`; or Invoicing itself, which owns the period, tells Collections the invoice is the first. Either needs no API call in the listener. Suggested: the additive fields, read as "not known" when null (rule 7), with `InvoicingApi` as the fallback for an event stored before them.
- **Retry 2's decline (PR-4).** Today nothing tells Collections that retry 2 was declined, so "OVERDUE when retry 2 is declined" (rule 1) cannot be built without a change. The change is additive:
  1. Invoicing publishes `InvoicePaymentDeclined` also for a declined retry of an invoice already `PAYMENT_FAILED`. Its one listener today, Collections, already treats a second decline for an open case as "nothing to open".
  2. `InvoicePaymentDeclined` gains a nullable `retry` (null for the first decline, and for an event stored before the field).
  3. `PaymentFailed` gains the same nullable `retry`. It is Payment's collection request's retry number, which `collectAgain(reference, retry)` already stores; **not the attempt number**, because a technical failure also makes a new attempt (ADR-013).
  
  Rule 2 holds: only a decline is published (`FailureReason.UNKNOWN` is ignored by Invoicing today, and a technical failure publishes nothing).
- **Not a stop condition:** every change above is additive (rule 7, ADR-016). The brief stops only for a non-additive one.

## S-2. Kill Bill's overdue module and billing

Read in Kill Bill's code at commit `3868a5b2339c3b86531904d6738a36e1e82933bf` (the commit killbill-wiki cites), fetched for this spike. The wiki's page `flows/overdue-dunning.md` agrees with what follows; nothing below rests on the wiki alone. One point is marked **inference**.

### Findings

1. **Billing is blocked exactly when the overdue state disables entitlement.** `blockBilling(state)` returns `state.isDisableEntitlementAndChangesBlocked()` ([OverdueStateApplicator.java:267-268](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/overdue/src/main/java/org/killbill/billing/overdue/applicator/OverdueStateApplicator.java#L267)). Tally's BLOCKED likewise stops both access and new invoices (I6).
2. **On the transition into a billing-blocked state, overdue tags the account `AUTO_INVOICING_OFF`; on the way out it removes the tag** ([:176-183](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/overdue/src/main/java/org/killbill/billing/overdue/applicator/OverdueStateApplicator.java#L176), [:237](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/overdue/src/main/java/org/killbill/billing/overdue/applicator/OverdueStateApplicator.java#L237), [:245](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/overdue/src/main/java/org/killbill/billing/overdue/applicator/OverdueStateApplicator.java#L245)). The method is named `avoid_extra_credit_by_toggling_AUTO_INVOICE_OFF`. It stores the new state last, because the entitlement DAO then posts the event that invoice reacts to ([:139-142](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/overdue/src/main/java/org/killbill/billing/overdue/applicator/OverdueStateApplicator.java#L139)).
3. **The blocked time is taken out of billing.** The junction inserts a disable event at the start of the blocked duration, with no price and `NO_BILLING_PERIOD`, "which makes invoice disregard this event" ([BlockingCalculator.java:247-256](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/junction/src/main/java/org/killbill/billing/junction/plumbing/billing/BlockingCalculator.java#L247)), and a re-enable event at its end, "populated with the event state from before the blocking period, for invoice to resume invoicing", **with the same bill cycle day** ([:284-287](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/junction/src/main/java/org/killbill/billing/junction/plumbing/billing/BlockingCalculator.java#L284)).
4. **While billing is blocked, the renewal date passes with no invoice.** `TestOverdueIntegration.testOverdueStages2`: billing blocked on 2012-07-10; on the billing day, 2012-07-31, "No NEW INVOICE because OD2 -> still blocked" ([TestOverdueIntegration.java:283](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/beatrix/src/test/java/org/killbill/billing/beatrix/integration/overdue/TestOverdueIntegration.java#L283)).
5. **When the debt is paid, the billing day has not moved, and the blocked days of the period already invoiced are credited back.**
   - Paid on the billing day (`testOverdueStages2`, [:291-299](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/beatrix/src/test/java/org/killbill/billing/beatrix/integration/overdue/TestOverdueIntegration.java#L291)): a new invoice for 07-31 to 08-31 at the full price, 249.95, and a credit (`REPAIR_ADJ`) of −169.32 for 07-10 to 07-31, the blocked days.
   - Paid in the middle of the period (`testOverdueStages1`, [:189-196](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/beatrix/src/test/java/org/killbill/billing/beatrix/integration/overdue/TestOverdueIntegration.java#L189)): blocked from 07-10, paid on 07-20: a credit of −80.63 for 07-10 to 07-20, kept as account credit (`CBA_ADJ`) and used on the next invoice, on the unchanged billing day, 07-31.
   - **Inference:** how invoice computes these amounts (day by day over the period) was read only in the tests' expected items, not in invoice's generator.
6. **An overdue state may also cancel the subscriptions**: `cancelSubscriptionsIfRequired`, with the state's policy `NONE`, `END_OF_TERM` or `IMMEDIATE` ([:285-314](https://github.com/killbill/killbill/blob/3868a5b2339c3b86531904d6738a36e1e82933bf/overdue/src/main/java/org/killbill/billing/overdue/applicator/OverdueStateApplicator.java#L285)). Tally's write-off ends the subscription in the same spirit (the brief, "A subscription can end").

### Compared with decision 3 (postpone the renewal while BLOCKED)

| | Kill Bill | Tally, decision 3 option A |
|---|---|---|
| A new invoice while blocked | None (finding 4) | None (I6) |
| The billing day after the block | Unchanged (finding 3) | Unchanged: the period keeps its dates |
| The blocked days | Credited back, day by day (finding 5) | Not credited: "a household restored on 5 April pays for all of April" (the brief's stated cost) |
| The mechanism | Recompute billing without the blocked duration, and a tag that stops invoicing | The renewal waits on the queue while BLOCKED |

So Kill Bill confirms the first half of decision 3, and shows that the cost the brief names is real and removable: it is removed by proration, which the brief leaves to the billing lifecycle slice (WAR-62). Nothing here contradicts an accepted record, and nothing changes this slice's plan.

## S-3. Thirty days in a test (D10)

`SpikeCollectionsS3ThirtyDaysIT.thirtyDaysWithFourTimersAndARenewalRunInUnderOneMinute`.

- **What runs:** sign-up; the first charge declined (the fake in `DECLINE_HARD(100)`); the case opened; Collections' real day-3 retry, declined; Subscription's real renewal at the end of the first period, its charge declined. Days 7, 10 and 30 have no code yet, so three items on the platform's test queue (`platform_test.work_queue`) stand in for them, due at the same times and handled by the same pollers.
- **How the clock moves:** one day at a time, 32 times, waiting before each move until the account's work is done (`Quiesce`), as AT-22 will. Pollers run at 100 ms (`AbstractReconcilingTest`).
- **Measured:**

  ```
  SPIKE-C-S3 set-up 546 ms; milestones (day=ms since start) {3=1001, 7=1556, 10=1981, 30=4185}; renewal of period 2026-02-15 to 2026-03-15 (first period from 2026-01-15) invoiced on day 31, its charge declined; total 4923 ms for 32 days
  ```

  The full `./mvnw verify` before the commit measured it again: `total 4441 ms for 32 days`, with the same milestones (day 3 at 0.8 s, day 30 at 3.8 s).

  The whole test class took 21 s, with its Spring context (a database of its own, ADR-029).
- **What it means:** D10's minute leaves room for AT-22's real timers, the standing's way to Access in a container (AT-1 measured 0.7 s per hop), and the checks. The minute is not at risk on this harness.

## S-4. A `set` during a sign-up

`SpikeCollectionsS4SetDuringSignUpIT.aSetThatCommitsInsideASignUpsWindowGivesTheSignUp500Today`.

- **How the window is held:** the test locks `subscription.subscription` in SHARE mode. The sign-up has read the account (version 0) and waits on its subscription insert:

  ```
  SPIKE-C-S4 waiting: [{wait_event=relation, query=insert into subscription.subscription (…)}]
  ```

  `AccountStanding.set(OVERDUE)` commits meanwhile (version 1); the lock is released.
- **Answer:** `500`. The log: `ObjectOptimisticLockingFailureException … StaleStateException: Unexpected row count (expected row count 1 but was 0) [update account.account set … payment_token=?, … version=? where id=? and version=?]`.
- **And:** no subscription is kept for the account; its standing is OVERDUE.
- **This is the #65 review's reading,** now driven by a test. PR-4 turns the assertion into 409 with `Retry-After` (WAR-57, option a).

## Questions for the user

1. **S-1, retry 2:** build the three additive changes above in PR-4 (Invoicing publishes a declined retry; `retry` on both events)? They touch `invoicing` and `payment`, which the brief's "Target shape" table does not list, though they need no new dependency.
2. **S-1, the first invoice:** the additive `subscriptionId` and `periodNumber` on `InvoicePaymentDeclined`, or a call to `InvoicingApi` from Collections' listener?
