# Slice 3 report: Collections

Slice 3 built the Collections timetable: a household that does not pay is OVERDUE, then BLOCKED, then written off, and a household that pays at any step can watch again. It ran from PR #75 to PR #83, all merged, and this report is PR-9. Record numbers follow CLAUDE.md: `ADR-NNN` for the second design, `FR-NNN` for the first run, `Dn` for drivers.

**The brief's goal, and where it stands:**

| When this slice is done | State | Evidence |
|---|---|---|
| A household that does not pay is OVERDUE after the second retry, BLOCKED on day 10, and written off on day 30 | Yes | AT-22 (#79) |
| A blocked household cannot watch: Access says no, `ACCOUNT_BLOCKED` | Yes | AT-22 (day 10, with a higher sequence); AT-23 (c) |
| A household that pays, at any step, can watch again | Yes | AT-23 (a) to (d) (#80, #81); AT-25 (#80) |
| A write-off ends the subscription; no renewal follows | Yes | AT-22 (day 30; no invoice after the next renewal date) |
| A test runs the 30 days in under 1 minute (D10) | Yes | AT-22: 33 days in 9.0 s |
| Access does not change | Yes | No change in `access/src/main` or in `contracts/access-facts/v1/schema.json` in the whole slice. One example file was added (#77) |
| Slice 2's step 0b: the nightly run | **Finished** (15 November, before this slice) | The user enabled the workflow `Nightly load` on 15 November; its state is `active`. Two runs by hand on `main` (1b18edc) passed: 43696821632 and 43698158614, with AT-10 at 10,000 renewals. Two scheduled runs passed since: 43843115838 (16 November) and 44067979012 (17 November). The Slice 2 report said "Not finished" and was corrected in this pull request (#84 review) |

**Builds at the end of the slice** (`main` at 7ae7017, after #83): `./mvnw verify`, JDK 25, BUILD SUCCESS. Billing 156 unit and 264 integration tests; Access one more unit test than at the end of Slice 2 (`ExamplesTest.anEndedSubscriptionLeavesNoSource`, with the new example `subscription-ended.json`). Billing's acceptance stage: 64 acceptance and spike tests (#83). promtool: 24 rules, every unit test passing (#83). CI green on every merged head; one commit stage was red on the way (#79, §3.3).

**Documents updated in this pull request:** the glossary (the case, the schedule, a step, overdue, write-off, "pay now", the retry number, the standing, an ended subscription, a waiting renewal), design.md §4 (the new APIs of `subscription`, `invoicing` and `collections`, and `ENDED`), and the topic tracker (C-06, D-07, D-09 and D-10, in the `.md` and the `.csv`; the `.csv` also gets A-14 and D-26, changed in the `.md` by #77). design.md §5 and §8 and `openapi.yaml` were updated by the pull requests that changed them (#76, #79, #80).

**Where the user's decisions are recorded.** Each decision given in chat is posted verbatim as a comment on the pull request it concerns, before it was acted on (CLAUDE.md, "How to work"). The architecture reviews are comments on the same pull requests. This report cites them by pull request.

| PR | Brief item | Merged |
|---|---|---|
| #75 | PR-0, the four spikes | yes |
| #76 | PR-1, the documents adopt the brief (rows 025, 037, 038; design.md §5) | yes |
| #77 | PR-2, a subscription can end | yes |
| #78 | PR-3, the case covers every unpaid invoice | yes |
| #79 | PR-4, the timetable and the standing (AT-22) | yes |
| #80 | PR-5, "pay now" (AT-23, AT-25) | yes |
| #81 | PR-6, the renewal waits while BLOCKED (AT-23 (d)) | yes |
| #82 | PR-7, the first invoice (AT-24; AT-16 changed) | yes |
| #83 | PR-8, alerts and runbooks | yes |

## 1. What was built, per acceptance test

| AT | Status | Test | What it shows | Result |
|---|---|---|---|---|
| AT-16 | changed (#82) | `AT16DeclinedPaymentIT`: `aDeclineOpensOneCaseTheDayThreeRetryCollectsAndThePaymentClosesIt`, `aLyingDeclineFollowedByTheDayThreeRetryChargesOnceAndClosesTheCase` | The decline is on a **renewal**, not on the first invoice (025 ends the subscription instead). The rest as before: one case; on day 3 a new attempt with its own key, or, for a charge reported as declined, a correction and no new attempt; the case closes | pass (2) |
| AT-22 | new (#79) | `AT22HouseholdNeverPaysIT.theHouseholdThatNeverPaysIsOverdueThenBlockedThenWrittenOff` | Sign-up on 1 February 2027; the 1 March renewal is declined. Attempts `[1=DECLINED, 2=DECLINED, 3=DECLINED]`. Day 7: OVERDUE, Access yes (sequence 2). Day 10: BLOCKED, Access no, `ACCOUNT_BLOCKED` (sequence 3). Day 30: WRITTEN_OFF, subscription `ENDED`, standing stays BLOCKED. No invoice after 1 April. **33 days in 9.0 s** | pass |
| AT-23 | new (#80; (d) #81) | `AT23HouseholdPaysIT`: `a_…`, `b_…`, `c_…`, `d_…` | (a) Day 3: the retry succeeds, the case closes, no `StandingChanged` was ever published. (b) Day 8, OVERDUE: "pay now" (retry 3) succeeds, standing GOOD. (c) Day 12, BLOCKED: "pay now" succeeds, standing GOOD, **Access says yes again in 0.72 s** (limit 5 s), sequence 3 → 4. (d) The 1 February renewal is declined; the 1 March renewal (day 28) waits while BLOCKED; "pay now" on day 29; **the renewal that waited ran 60 min after GOOD** (limit one day), its invoice paid, Access yes | pass (4) |
| AT-24 | new (#82) | `AT24FirstChargeDeclinedIT.aDeclinedFirstChargeEndsTheSubscriptionAndTheHouseholdSignsUpAgain` | The first charge is declined: subscription `ENDED FIRST_PAYMENT_DECLINED`, no case, standing GOOD, Access no, `NO_ACTIVE_SOURCE` (sequence 2). The same account signs up again with a card that works: Access yes (sequence 3) | pass |
| AT-25 | new (#80) | `AT25PaidAfterTheWriteOffIT.aWrittenOffHouseholdThatPaysIsGoodAgainAndSignsUpAgain` | Day 35, WRITTEN_OFF: "pay now" succeeds; standing GOOD; the subscription stays ENDED; the account signs up again (201) and Access says yes | pass |
| AT-1 to AT-21 | kept | as in the Slice 2 report | Unchanged; they pass in every acceptance stage of the slice | pass |

AT-22 to AT-25 ask the Access service in a container, as AT-1 does since Slice 2. AT-23 to AT-25 share `AbstractCollectionsAcceptanceTest`. AT-23 has four cases where the brief named three: on day 12, case (c), no renewal date has come, so (c) cannot hold the clause "the renewal that waited runs within one day". The user chose a separate case (d) on #81, and the brief's AT-23 row says so.

## 2. Spike findings

The four spikes are in `docs/briefs/slice-3-spikes.md`, each pinned by a test or a citation (#75). What became of each:

| Spike | Finding | What the slice did |
|---|---|---|
| S-1 | The first invoice can be told through `InvoicingApi` with no change. A declined retry publishes no second `InvoicePaymentDeclined`, and no event carries a retry number (`SpikeCollectionsS1DeclineDataIT`) | The user decided on #75: OVERDUE by a timer one hour after the day-7 retry is asked (no event added), and the first invoice through `InvoicingApi` (option A). Built in #79 and #82. The spike now pins the other side: a declined renewal is not the earliest invoice (#82) |
| S-2 | Kill Bill's overdue module stops invoicing while billing is blocked, never moves the billing day, and credits the blocked days of a period already invoiced | Decision 3 agrees on the first two. The third, the credit, is not built: Tally has no credit note (§4.4) |
| S-3 | 32 days in 4.9 s on the existing harness (D10) | AT-22 runs 33 days in 9.0 s with the real timers. The spike is kept and now measures the real write-off (#79, #82) |
| S-4 | A `set` during a sign-up gives the sign-up 500 | Fixed in #79 (WAR-57): 409 `/problems/concurrent-change` with `Retry-After`, not stored; the same key then signs up. The spike asserts it |

## 3. Deviations from the brief or the decision records, and why

### 3.1 Deviations

| # | What | Why | Where |
|---|---|---|---|
| 1 | OVERDUE is a timer, one hour after the day-7 retry is asked, not "when retry 2 is declined" | No event tells Collections that a retry was declined (S-1). Decided by the user on #75; rule 1 of the brief changed in #76 | #79 |
| 2 | AT-23 has a case (d) | (c)'s last clause cannot hold on day 12. The user's choice, recorded on #81 | #81 |
| 3 | "Pay now" numbering: every request of a case, scheduled or "pay now", takes the next retry number when it is asked; a scheduled retry is known by its step (`DAY_3`, `DAY_7`) | The brief's "next retry number of the case" let an early, declined "pay now" remove the automatic retries (#80 review, finding 1). Option B, confirmed by the user on #80; the brief's sentence and row 037 changed | #80 (V42) |
| 4 | The renewal key carries the subscription (`<account>:<subscription>:<period end>`) | Topic tracker D-26 had to be fixed before "one subscription per account" was lifted: an account signing up again on the same billing day would lose its renewal | #77 |
| 5 | An ended source is kept in its own table, `accessfeed.ended_source`, not as a state in `fact_source` | The previous release reads every `fact_source` row's state with `valueOf`; an unknown state there would fail its listeners during a rolling deploy (ADR-009). The tombstone stops a late STARTED from bringing the source back | #77 (V38) |
| 6 | `EndReason` has `UNKNOWN` as its `@JsonEnumDefaultValue`; `end` refuses it | FF-12 requires it of every enum in a module's API | #77 |
| 7 | A decline also joins a WRITTEN_OFF case | The guard `collection_case_one_unsettled_per_account` allows one case with a debt; after a write-off only a repeated decline can arrive, and without the join it would fail for ever | #79 |
| 8 | `concurrent-change` (409 with `Retry-After`, not stored) is a platform answer for every endpoint with an `Idempotency-Key`, not only sign-up | After a rollback nothing changed, for any endpoint (#79 review agreed) | #79 |
| 9 | A new `SubscriptionApi.end(subscriptionId, reason)`, besides `endLiveSubscriptions(accountId, reason)` | A late repeat of an old first-invoice decline must never end the household's new subscription | #82 |
| 10 | `collections` has a web layer (`adapter.web`); F10 checks it | "Pay now" is Collections' endpoint | #80 |
| 11 | `CollectionsStepLate` is a ticket, not a page | A late step delays a block or a write-off; when it runs it acts on its rule, from the state it finds, and asks Invoicing first. No money moves (#83 review agreed) | #83 |
| 12 | The tests that opened a case from a first-invoice decline now decline a renewal (`AbstractReconcilingTest.declinedRenewal`) | 025: a declined first invoice opens no case | #82 |

### 3.2 Where the code disagrees with the design, and what was done

- **design.md §2, I6 "checked at issue, corrected by credit note".** The check at issue is built (#81). The correction is not: Tally has no credit note, so the window in §4.4 leaves a household owing for a month in which it could not watch.
- **Brief rule 5, "the case's version orders a race".** The race is ordered by the case's row lock (`SELECT … FOR UPDATE`), taken by every decline that joins, every `InvoicePaid` and every timer. The version column is still there and still checked, but it is not what orders them. The end state the rule asks for holds (`aPaymentAndTheDayTenTimerAtTheSameTimeEndClosedAndGood`).
- **Glossary, "Standing: good, overdue, blocked, written off".** The code has three standings (GOOD, OVERDUE, BLOCKED); "written off" is a state of the case, and its standing is BLOCKED (decision 4). The glossary is corrected in this pull request.

### 3.3 Bugs found during the slice

| Bug | Found by | Fixed |
|---|---|---|
| An event with no sequence brought an ENDED source back as ACTIVE | #77 review, finding 1 | #77: an ENDED source never changes (`AccountFactsTest`) |
| A decline delivered again while its invoice was being paid could reopen a case with a paid invoice; the timers would then block a household that paid | #78 review | #79: every timer asks `InvoicingApi` first (`aTimerAsksInvoicingAndClosesACaseWhoseInvoiceIsPaid`) |
| Two declines at the same time failed with a duplicate key: V40 kept V24's index beside the new one, outside the insert's `ON CONFLICT` arbiter | CI on #79 (commit stage). Reproduced in PostgreSQL 17: 11 of 15 runs with both indexes, 0 of 15 with the new one only | #79: V40 drops V24's index |
| An early, declined "pay now" removed the automatic retries | #80 review, finding 1 | #80: option B |

## 4. Open questions and suggested new decision records

### 4.1 From the reviews of #75 to #83

| From | Note | Status |
|---|---|---|
| #76 review 2 | Timers act on their rule, not on one start state; test a late timer | Done in #79 (`timersThatRunLateEndWhereTheirRulesSay`, `theDayTenTimerBlocksACaseThatIsStillOpen`) |
| #77 review | **A new event type and an old release.** An instance of the previous release reads an `ENDED` event as UNKNOWN, counts it, and completes the publication. The feed never gets the fact, and nothing tries again. So "a consumer ignores a value it does not know" (rule 7) loses a fact when the value matters. V37's comment, "by the time a subscription can end, every release knows ENDED", holds here only because no production database exists | **Open question**: the release that first produces a new event type must come one release after the one that can read it (§4.3) |
| #78 review | Three points for PR-4: a timer asks Invoicing first; a decline joins OVERDUE and BLOCKED cases, and `InvoicePaid` finds them and WRITTEN_OFF ones; Payment accepts a retry number that skips 1 | Done in #79 |
| #79 review, 1 | **V40 gives the open cases of today the full timetable**, counted from their first decline: a case 40 days old is blocked and written off minutes after the deploy | No production database, so nobody is harmed. **A real rollout needs a decision first: a notice period for cases already open** (§4.3) |
| #79 review, 2 | **The restore can wait for a resubmission.** `on(InvoicePaid)` sets the standing in its transaction. If another commit changes the account at that moment, the listener fails, and the household stays blocked until the publication is resubmitted (5 minutes, ADR-016's resubmission) | Not measured. AT-23 (c) measures the normal case (0.72 s) |
| #79 review, 3 | **Rolling deploy:** the previous release reads a case's status with JPA's `valueOf` and fails on a case in a new state (its case read, and its day-3 retry of that case, which the queue tries again). Its listeners select OPEN cases only | Stated in V40's header (§4.4) |
| PR-5 (found by `PayNowIT`) | **A late retry 2 does nothing while retry 1's attempt is open.** When retries 1 and 2 come due together (a stop, a clock jump), retry 2 finds retry 1's attempt REQUESTED; Payment makes nothing for it, and the case records it as asked. Normally they are four days apart | Known limit (§4.4) |
| #80 review, 1 | **A 202 can do nothing.** If an attempt for the invoice is still open, Payment starts no second one: the request is accepted, and no charge is tried | Known limit (§4.4) |
| #80 review, 2 | **No limit on "pay now".** Each press with a new key is one more charge attempt on a card that was declined. A real provider may punish many retries on a declined card. Not verified against the card schemes' rules | **Open question**: a limit for each case and day (§4.3) |
| #80 review 2 | **A queued "pay now" can be overtaken.** It takes number N and waits on the queue; before the poller reaches it, the day-3 step takes N+1; Payment then refuses N. Nothing is lost: one request was made | Known limit |
| #81 review, 1 | **The window of I6.** The standing is read before the renewal's transaction; a block set just after that read lets one renewal through, and its invoice joins the case. No credit note exists, so the household owes for a month it could not watch | Known limit (§3.2, §4.4) |
| #81 review, 2 | **The cost of waiting:** one claim and one read of the account each hour, for each blocked account's renewal | Known limit; small at this size |
| #81 review, 3 | **`attempts` loses its meaning for renewals:** it counts hours of waiting, not failures | Done in #83: F14 fails any rule that reads attempts; the rules file, `billing-stalled.md` and `collections-step-late.md` say so |
| #82 review, 1 | **An invoice with no subscription is "not a first invoice"**, so its decline opens a case. Every invoice the code makes today has a subscription; only tests make others | **A later invoice type (a one-off charge) needs a decision** (§4.3) |
| #82 review, 2 | **The first invoice stays `PAYMENT_FAILED`** after its subscription ends, and so does an invoice of a written-off subscription (the brief puts invoice states out of scope) | **A later slice needs a "not owed" (void) state**, so that reports do not count it as a debt (§4.3) |
| #82 review, 3 | Two reads of Invoicing for each decline (the account's invoices, then the subscription's) | Known limit; declines are rare |
| #83 review | **The F14 attempts check is broad:** it matches "attempt" anywhere in an expression, so it would refuse a future metric such as a count of payment attempts | If that happens, narrow it to the queue's `attempts` column; do not remove it |

### 4.2 Proposed records, as built

| Record | As built | Suggested "Checked by" |
|---|---|---|
| 025 (the first invoice) | Collections asks `InvoicingApi`: the earliest period of the subscription is the first invoice; its decline ends that subscription (`SubscriptionApi.end(subscriptionId, FIRST_PAYMENT_DECLINED)`); no case | AT-24; `FirstInvoiceDeclinedIT` |
| 037 (the Collections case) | As written, with #80's numbering. The race is ordered by the case's row lock (§3.2) | AT-22, AT-23, AT-25; `CaseTimetableIT`; `CaseCoversEveryInvoiceIT`; `PayNowIT`; `CollectionsStepLate` with promtool tests |
| 038 (a subscription can end) | As written; the access feed keeps an ended source in `ended_source` | `SubscriptionApiIT`; `SubscriptionTest`; `RenewalIT`; `NextRenewalScheduledIT`; `AccessFeedIT`; `AccountFactsTest`; AT-22; AT-23 (d); AT-24 |

### 4.3 Suggested new decision records

1. **A new event type ships one release after its readers** (from #77). The release that first produces a value that matters to a consumer (here `ENDED`) comes after the release that can read it; until then the producer does not produce it. This would extend 016 and 009.
2. **A limit on "pay now"** (from #80): for each case, a number of requests a day, after which the endpoint answers 429. Needs the card schemes' rules first.
3. **A notice period before the timetable applies to cases already open** (from #79), for the first rollout with real data.
4. **A "void" invoice state** (from #82): an invoice of an ended or written-off subscription that is not owed, so reports and the ledger do not count it as a debt. With a credit note, it would also close the I6 window (#81).
5. **What "first invoice" means for an invoice with no subscription** (from #82), before a one-off charge exists.

### 4.4 Known limits

- **Rolling deploys** (ADR-009), each stated in its migration's header: V37's previous release would renew an ended subscription (nothing ends one before #79, so it cannot happen here); V39's previous release acts on one invoice per case; V40's previous release fails on a case in a new state.
- **I6's window** and the missing credit note (§3.2).
- **"Pay now"** has no limit; a 202 can do nothing while an attempt is open; a queued request can be overtaken by the day-3 step.
- **A late retry 2** does nothing while retry 1's attempt is open.
- **The restore can wait for a resubmission** when the account changes at the moment `InvoicePaid` is handled.
- **Invoice states:** an invoice of an ended or written-off subscription stays `PAYMENT_FAILED`.
- **The cost of a waiting renewal:** one claim and one read an hour for each blocked account.

## 5. Fitness functions added

| Check | What it fails | Since |
|---|---|---|
| F14 `noRuleReadsAQueuesAttempts` | Any alert rule whose expression reads attempts | #83 |
| promtool tests for `CollectionsStepLate` | The rule not firing at 65m30s, or firing for a step run within the hour, for the retry queue, or for a waiting renewal | #83 |
| F10 `collectionsIsBuiltInClosedLayers` | Collections' web layer reaching past the module's API, or a lower layer calling it | #80 |
| FF-12 `EnumsHaveUnknownDefaultTest` | `EndReason` without `UNKNOWN` (the pinned list now names it) | #77 |
| F7 `theExamplesAreVersion1ExceptTheFutureOne` | `subscription-ended.json` missing or invalid | #77 |
| `ContractIT.payNow` (and the two checks over every operation) | "Pay now" not in `openapi.yaml`, or an answer that does not conform | #80 |
| Invariant `subscription.next-renewal-scheduled` | Counts live subscriptions only; a waiting renewal still counts as scheduled | #77, #81 |

## 6. Audit of the decision records this slice touched

The "Checked by" column of `docs/architecture/v2/README.md`, against the code. A check counts only if a test exists. Rows 025, 037 and 038 are proposed, and that table has no "Checked by" column; for them the audit compares the record's text with the tests that exist (§4.2 suggests the column's entry).

| Record | The record says | Check in the code | Gap |
|---|---|---|---|
| **ADR-005** (Collections orchestrated by its own state machine with timers). Checked by: state-machine tests | Collections owns its timers and its states | `CaseTimetableIT` (each step on its day; late timers; the day-10 timer from OPEN; a timer that finds the invoice paid); `CaseCoversEveryInvoiceIT`; `PayNowIT`; AT-22; `SubscriptionTest` (ENDED refuses `renew`) | **No unit test of `CollectionCase.take`** by itself: its ranks are tested only through the database, in `CaseTimetableIT`. Suggested column: `CaseTimetableIT`; `SubscriptionTest`; AT-22 |
| **ADR-013** (payment outcomes; only a decline starts Collections). Checked by: AT-4, transition test | A technical failure never starts Collections | AT-4 (no case after technical failures, unchanged); 037's "only a decline opens a case" | None new. **The OVERDUE timer reads no payment outcome** (by design, #75), so no test relates the two |
| **ADR-016** (envelope; add-only; `UNKNOWN`; resubmission). Checked by: FF-12; AT-17 | New values are added, never renamed; readers handle unknown values | FF-12 now names `EndReason`; `CaseStatus` keeps its `UNKNOWN` default with its new values; `SubscriptionChangeType.ENDED` added, nothing renamed; the feed counts an unknown type (`AccessFeedIT.aValueTheFeedDoesNotKnowChangesNothingAndIsCounted`) | **The loss in §4.1 (#77)** has no check: nothing stops a release from producing a value its previous release cannot read. That is what the record in §4.3, 1 would add |
| **ADR-025** (the first invoice), proposed | A declined first invoice ends the subscription; no case; Access `NO_ACTIVE_SOURCE`; the household signs up again | AT-24; `FirstInvoiceDeclinedIT` (2) | None |
| **ADR-028** (pinned listeners). Checked by: `PinnedListenersTest` | No listener renamed or moved while it may have incomplete publications | `PinnedListenersTest` passes: the slice renamed and moved no listener (Collections' two, the feed's two); the review of #82 checked it | None |
| **ADR-033** (the access feed) | (1) facts change only on a change, sequence +1 | `AccessFeedIT` (3 new: ENDED leaves and the sequence goes up; an ENDED that arrives first stays ended; a new subscription after an ended one); `AccountFactsTest` (an ENDED source never changes, whatever the sequence) | None |
| | Access reads it with no change | `ExamplesTest.anEndedSubscriptionLeavesNoSource`; no change in `access/src/main` or `schema.json` | None |
| | Rolling deploys | The tombstone table keeps the previous release's reads valid (V38) | **No test runs the previous release against the new schema** (as in Slice 2: no N−1 replay) |
| **ADR-037** (the Collections case), proposed | One case covers every unpaid invoice | `CaseCoversEveryInvoiceIT` (6: joins, two declines at once, closes only when none unpaid, payment and decline at once, the retry asks for each, a paid invoice's decline) | None |
| | The timetable; each timer only with a debt open | AT-22; `CaseTimetableIT` (8) | None |
| | The standing follows the case in one transaction; the end state is never "case closed, standing BLOCKED" | `aPaymentAndTheDayTenTimerAtTheSameTimeEndClosedAndGood`; `paidWhileBlockedClosesTheCaseAndTheStandingIsGood` | **Ordered by the row lock, not by the version** (§3.2) |
| | Each timer is a claimed row with a business guard | `case_step_one_per_case_and_step`, `case_retry_one_per_case_and_step`, `case_retry_one_per_case_and_number`; `timersThatRunLateEndWhereTheirRulesSay` reads the step rows | None |
| | "Pay now": every unpaid invoice, any state with a debt, the next number, a decline changes no state | `PayNowIT` (5); `ContractIT.payNow`; AT-23 (b, c, d); AT-25 | **No limit** (§4.1, #80) |
| | After a write-off the standing stays BLOCKED until paid, then GOOD; the subscription stays ended | `theWriteOffEndsTheSubscriptionAndAPaymentLaterMakesTheStandingGood`; AT-25 | None |
| | A late step is seen | `CollectionsStepLate` with promtool tests; `collections-step-late.md` | None |
| **ADR-038** (a subscription can end), proposed | Any live state → ENDED, `ENDED` add-only; ENDED refuses everything | `SubscriptionApiIT` (end publishes ENDED; two ends at once end it once); `SubscriptionTest`; `RenewalIT.theRenewalOfAnEndedSubscriptionRunsAndDoesNothing`; `FirstInvoiceDeclinedIT` (end by subscription) | None |
| | One live subscription per account; sign up again | `anAccountWithAnEndedSubscriptionSignsUpAgain` (with D-26's renewal row); AT-24; AT-25 | None |
| | The feed removes an ended source | `AccessFeedIT`; `AccountFactsTest` | None |
| | The renewal waits while BLOCKED; runs once GOOD; the period keeps its dates | `NextRenewalScheduledIT.aRenewalWaitsWhileTheAccountIsBlockedAndRunsWithinOneDayOfGood`; AT-23 (d) | **I6's window** (§3.2): no test, and no credit note |
