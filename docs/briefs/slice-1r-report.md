# Slice 1R report: the skeleton reshaped to the second design

Slice 1R reshaped the first run's walking skeleton to the second design (`docs/architecture/v2/`). It ran from PR #13 to PR #27, all merged.

**Definition of done** (brief, "Definition of done"):

| Criterion | State |
|---|---|
| All ten PRs merged | Yes. Two PRs were added to the plan: PR-3a (#17) and PR-9b (#24). Two fixes came out of the slice's own runs: #25 and #26. |
| `./mvnw verify` green on `main` | Yes, on main at `a96fe86` plus this PR. Results: fake-provider 19 tests; Tally 97 unit tests and 156 integration tests. |
| AT-1 to AT-17 pass | Yes, all 17 (§1). One gap: AT-4 does not assert technical failures directly. |
| The pinned graph equals design.md §3, with the one temporary edge marked | Yes, since PR-10 (#27). `PinnedModuleDependenciesTest` checks `module-dependencies.txt`. The `access → subscription, account` edge is marked TEMPORARY and narrowed to events by `AccessReactsOnlyToEventsTest`. |
| F3, F4, F8, F9 and F10 exist as tests | Yes (§5). |

Record numbers follow CLAUDE.md: `ADR-NNN` for the second design, `FR-NNN` for the first run.

**Where the user's decisions are recorded.** None of PR #13 to #27 has a GitHub review or review thread. The user's decisions were given in the conversation. Each PR description records the decisions it applied ("your #23 review, decisions 5 and 6"), and the plan comment on #13 records the twelve answers to the plan. This report cites them the same way.

## 1. What was built, per acceptance test

**Statuses:**
- **Kept:** the test is unchanged apart from comments.
- **Changed:** the scenario holds, but the test was updated to the new modules, states or REST contract.
- **New:** the brief added the scenario.

No acceptance test was retired.

| AT | Status | Test | What it shows | Result |
|---|---|---|---|---|
| AT-1 | changed | `AT01SignUpHappyPathIT.signUpPromisesAccessWithinFiveSecondsThenTheInvoiceIsPaidAndAccessGranted` | `POST /v1/subscriptions`, with an `Idempotency-Key`, answers `201` with `accessWithinSeconds: 5`. Access answers yes within 5 s. The invoice is paid with exactly one charge. Renamed from `AT01CheckoutHappyPathIT`: checkout's "succeeded within 3 s" became the 5-second promise (ADR-014). | pass |
| AT-2 | kept | `AT02MonthOfTimeTravelIT.oneMonthLaterEveryAccountHasExactlyOneNewInvoiceAndOneNewAttempt` | 100 accounts, one month of time travel: one invoice and one attempt per account; runs in under 2 min (D10). | pass |
| AT-3 | changed | `AT03CalendarEdgesIT.renewalsFollowTheLocalCalendarThroughShortMonthsLeapYearsAndDaylightSaving`; `subscription.domain.BillingCalendarTest` (11 tests) | Day 31, local midnight, daylight saving. Changed: the queue is now `subscription.renewal`, and `BillingCalendarTest` moved to `subscription.domain` (PR-5). | pass |
| AT-4 | changed | `AT04ProviderOutageIT.anOutageMidRunOpensTheBreakerAndEverythingIsChargedAfterRecovery` | The breaker opens. Nothing is claimed while it is open. Everything is charged once after recovery. **No Collections case opens.** Changed: checkout's `202` became sign-up's `201`, and the no-case assertion was added (#22, red step shown in #23). **Gap:** "technical failures" is implied (invoices stay PAYMENT_PENDING and no case opens) but never asserted as `TECHNICAL_FAILURE` attempt states. | pass |
| AT-5 | kept | `AT05TimeoutAfterChargeIT.aChargeThatTimedOutIsReconciledNotRetried` | A timeout after the charge gives UNKNOWN, resolved to SUCCEEDED; charge count 1 (D2). | pass |
| AT-6 | changed | `AT06LyingProviderIT.aChargeReportedAsDeclinedIsCorrectedAndTheInvoicePaid` | Unchanged, except that state `FAILED` became `DECLINED` (PR-6). | pass |
| AT-7 | kept | `AT07CrashBeforeListenerIT.collectionIsRequestedExactlyOnceAfterRecovery` | Collection requested exactly once (D3). | pass |
| AT-8 | kept | `AT08ListenerFailsIT.aFailedListenerIsResubmittedWithinTheLimitAndItsEffectHappensOnce` | Resubmitted; the effect happens exactly once. | pass |
| AT-9 | changed | `AT09KillOneInstanceIT.killingOneInstanceMidRunLeavesExactlyOneInvoicePerAccountAndDate` | One renewal and one invoice per subscription and period (D1). Changed: the queue is now `subscription.renewal`, with the new states. The attempt count is now *accounts + technical failures*, because a technical failure is retried as a new attempt (ADR-013). This PR corrects a stale comment that still cited the removed "consistent lookup" rule. | pass |
| AT-10 | changed | `AT10ThroughputIT.renewalsAreChargedAtTheProvidersRateWithoutA429Storm` | Below 1% 429s (D6). Changed: a 429 is now a `TECHNICAL_FAILURE` retried as a new attempt, so attempts = renewals + rate-limited. | pass |
| AT-11 | kept | `platform.internal.AT11StopEachWorkerAndJobIT.everyStoppedWorkerAndJobShowsOverdueWithinItsThreshold` | Every stopped worker and job shows overdue within its threshold (D9). | pass |
| AT-12 | kept | `AT12InvariantCheckIT.anInjectedDuplicateInvoiceRaisesTheViolationsMetric` | `tally.invariant.violations` rises. | pass |
| AT-13 | new | `AT13SameSignUpTwiceIT`: `theSameSignUpTwiceWithOneKeyMakesOneSubscriptionAndIdenticalResponses`, `theSameSignUpTwiceAtOnceMakesOneSubscription`, `twoAccountsUsingTheSameKeyValueBothSucceed`, `aKeyReusedForADifferentRequestIsRefusedAndChangesNothing` | One subscription and identical responses. Concurrent requests give one subscription. Keys are scoped per account. A reused key gives `422`. | pass (4) |
| AT-14 | new | `AT14InvoiceLinesEqualQuoteIT.theInvoiceCarriesTheQuotesLinesTotalAndOfferVersion` | The invoice's lines, total, offer version and reason equal the `PriceQuote`. | pass |
| AT-15 | new | `AT15CrashAfterAttemptSentIT`: `theProviderChargedBeforeTheCrashSoTheAttemptResolvesAsSucceededAndIsNeverSentAgain`, `theCallNeverReachedTheProviderSoTheRepeatWithItsKeyChargesOnce` | A SENT attempt goes stale, becomes UNKNOWN and is resolved. It is charged at most once. | pass (2) |
| AT-16 | new | `AT16DeclinedPaymentIT`: `aDeclineOpensOneCaseTheDayThreeRetryCollectsAndThePaymentClosesIt`, `aLyingDeclineFollowedByTheDayThreeRetryChargesOnceAndClosesTheCase` | One case. On day 3, a new attempt for the same reference with its own key. Payment closes the case. The lying-decline case (proposed ADR-026) makes no new attempt and charges once. | pass (2) |
| AT-17 | new | `AT17UnreadablePublicationIT.oneUnreadablePublicationAmongOthersNeverBlocksTheRest` | An unreadable publication and a missing event class stay failed, and show in `tally.events.unreadable` and the incomplete count. The others are handled. | pass |

**Other behaviour built**, by PR:

| PR | Brief step | Built |
|---|---|---|
| #13 | PR-1 | Archived first-run records as `FR-NNN`; adopted the v2 log; CLAUDE.md updated; target graph pinned beside the current one |
| #14 | Spikes | S-1 and S-2 (§2) |
| #15 | PR-2 | `Ratio`, `Money.plus/minus/times(Ratio)/allocate`, minor units from `Currency`; F4 |
| #16 | PR-3 | `catalog` → `offers` (`V16`) |
| #17 | PR-3a (added in #13) | Spring Data repositories moved behind business-named domain ports; F8, F9, F10 |
| #18 | PR-4 | `Offer`, `OfferVersion`, `OfferKind`; `PricingRule` registry with `FixedPricePhaseRule` and `FreeTrialRule`; start-up check; `Pricing.quote` → `PriceQuote`; invoice lines; AT-14 |
| #19 | PR-5 | The billing calendar and `renew(n)` moved to Subscription, on its own queue; invoice ID from (subscription, period); pinned offer version; zero invoices issued PAID |
| #20 | PR-6 | Six attempt states plus `UNRECOGNISED`, in a transition table; technical failures retried with back-off; the stale rule; REQUESTED-age health |
| #21 | PR-7 | Sequence number on every event; resubmission one publication at a time; FF-13; AT-17 |
| #22 | PR-8 | `collections` (day 3 only); per-attempt provider key; resolver a/b/c; `collectAgain`; `InvoicePaymentDeclined`; F3; AT-16 |
| #23 | PR-9 | `POST /v1/subscriptions` (OpenAPI first, RFC 9457 problems); `checkout` removed; runbooks |
| #24 | PR-9b (added after #23) | Idempotency keys in `platform`, one table per module; `POST /v1/accounts` requires a key; `request-in-progress` with `Retry-After`; contract tests for every endpoint |
| #25 | fix | Fake provider takes a `Clock`; deterministic rate-limit tests |
| #26 | fix | Reference lookup decides only for the latest attempt |
| #27 | PR-10 | UUID-only `Idempotency-Key`; `entitlement` → `access` (`V28`); access from sign-up, no payment dependency; pinned graph switched to the target |

## 2. Spike findings

Evidence for both spikes: `docs/briefs/slice-1r-spikes.md`, which is written as of #14. Its remark "Tally, which sends no key yet" and its reference to design §5's `UNKNOWN → TECHNICAL_FAILURE` branch are history: PR-8 changed both.

**S-1: can Spring Modulith 2.1.1's resubmission run one publication at a time? Yes.** Evidence: `spikes.SpikeS1ResubmitOneAtATimeIT`, 7 experiments, all passing.
- `s1aOlderThanFailsOnOneUnreadableRow`: the usual API stops at one unreadable row. This reproduces the first run's finding 1.
- `s1bPredicateSelectingOtherRows`: `resubmitIncompletePublications(Predicate)` runs the predicate before the event is read.
- `s1dPredicateCatchingPerRow`: so a predicate that calls `getEvent()` inside try/catch isolates a bad row. **This is the design built in #21.**
- `s1cOptionsWithFilterAndBatchOfOne`: `ResubmissionOptions` is unusable, because it reads its batch before filtering.
- `s1eOneCallPerPublication`: one call per publication works, but is O(n²). It is the fallback.
- `s1fMissingEventClass`: a row whose event class is missing never reaches the predicate (only a WARN is logged), so only the incomplete count shows it.
- `s1gPredicateCoversCrashLeftPublications`: the predicate has no minimum age of its own, so it must check age itself.

ADR-016 holds, with those two additions: the minimum age is the predicate's job, and unreadable rows are reported but never resubmitted.

**S-2: the fake provider did not model idempotency keys or status by key.** Evidence: `fake-provider/.../IdempotencyKeysTest`. Before the change: 7 tests, 5 failures and 1 error. After: 7 pass.
- **Now modelled on Stripe v1:** CONTRACT.md A22 and A23.
- **Finding: no real lookup by key exists.** "Resolve by key" means repeating the request with its key, which has three edges:
  - if the first request never executed, the repeat charges now;
  - after retention (24 h), a repeat can charge twice;
  - while the first request still executes, a repeat gets `409`.
- **Consequences:** each attempt needs its own key, and the lookup by reference (A11) stays necessary. These findings became proposed ADR-026 (§4).

## 3. Deviations from the brief or the decision records, and why

### 3.1 Deviations

1. **Two PRs added to the plan, by the user's decision:**
   - **PR-3a (#17):** the inside layout (rule 14, F8 to F10) had to come before PR-4;
   - **PR-9b (#24):** idempotency keys moved into `platform`, and `POST /v1/accounts` was covered.
2. **Two fixes outside the plan, both found by the slice's own runs:**
   - **#25:** the fake provider read the wall clock directly, which broke rule 4 and made two tests flaky;
   - **#26:** the AT-16 reconciliation bug (§3.3).
3. **One-step migrations**, which ADR-009 (expand and contract) would normally forbid. Each PR said so; all are safe only because no production database exists:
   - `V16`: `catalog` → `offers`;
   - `V17`/`V18`: offers;
   - `V20`: the calendar moves to Subscription; its data moves between schemas once (rule 2);
   - `V21`: state mapping, where every `FAILED` row becomes `DECLINED`;
   - `V22`: per-attempt key;
   - `V27`: the idempotency key's `scope` column;
   - `V28`: `entitlement` → `access`.
   
   `V26` was edited in place before it reached main.
4. **Access from sign-up for every subscription (H7)** (#13 answer 8; built in #27). The first run's setting `tally.access.grant-before-payment` (FR-019, Proposed) was removed. Proposed ADR-025 (a first-invoice decline ends the subscription) is **not built** (§4).
5. **`AccountRepository` stays in `account.domain`** (#17). `account` is built in closed layers (F10), not ports and adapters, so F9 does not apply. The offer to move it was not taken up.
6. **The sequence number is a boxed `Long`** (#21), because Jackson 3 refuses a missing primitive (`FAIL_ON_NULL_FOR_PRIMITIVES`). This became FF-13 and a sentence in rule 7.
7. **`requestPaymentAgain(invoiceId, retry)`** (#22) has an idempotency parameter that design §4 lacked. §4 was corrected.
8. **Alert links** live in Micrometer meter descriptions (#22), because no alert-rule file exists yet (Slice 2, step 0).
9. **A new test-scope dependency** (#24): `com.atlassian.oai:openapi-request-validator-mockmvc` 3.0.0, on the user's four conditions:
   - test scope only;
   - its Jackson 2 version (2.21.5) managed by Spring Boot;
   - raw bytes validated;
   - shown catching a renamed field.
10. **`access.access.granted_by` still allows `PAYMENT_SUCCEEDED`** (#27), because existing rows may carry it.
11. **`Ledger` in the fake provider keeps `Instant.now()`** (#25). CONTRACT.md §7 says the fake stamps charges with its own clock on purpose.

### 3.2 Where the code disagreed with the second design, and what was done

| Disagreement | Evidence | What was done |
|---|---|---|
| F8, F9 and F10 were not defined in the repository, and seven repositories sat in domain packages (rule 14) | #13 plan, point 1 | PR-3a (#17): ports with business names; the three tests added |
| Under design §3, `collections` could not see payment events | #13, point 2 | New `InvoicePaymentDeclined` event from Invoicing (#22); design §4 updated |
| Invariant I2 (one invoice per subscription and period) had no identity to hang on | #13, point 5 | Invoice ID is a name-based UUID of (subscription, period), with a unique constraint (#19) |
| `AttemptState.UNKNOWN` meant both "outcome unknown" and "value not recognised" | #13, point 11 | Proposed ADR-024: `UNRECOGNISED` (#20) |
| design §6 named the Money field `minor`; the code says `minorUnits` | #15 | design.md corrected |
| design §4's `PriceQuote`, `QuoteRequest` and API names differed from the code | #18, #19, #20 | design.md corrected to the code's names (#13 answer 9) |
| R4 forbade `Offers.currentVersion`, which pinning the offer version needs | #19, decision A | R4 amended in drivers.md (#18, #20), and again when `isOffered` was removed (#23) |
| "Status by key" assumed a lookup by key; providers have none | S-2 (#14) | Proposed ADR-026 (resolver a/b/c) |
| design §5's stale rule included REQUESTED attempts, which were never sent | #20 review | SENT only. ADR-018's text untouched; the rule goes to proposed ADR-026; rule 9 and §5 changed |
| design §5's `UNKNOWN → TECHNICAL_FAILURE` on a "consistent not found" | #22 review | Removed: "not found" alone never means "no charge" |
| A decline found by reference could decide a later attempt | #22 (PR-6 question 3) | A decline decides only the first attempt |
| A charge found by reference could be booked to the wrong attempt | #26: AT-16 run, `Corrected INV-1125… from DECLINED to SUCCEEDED` | The lookup decides only for the latest attempt; README row 026 and design §5 updated (this PR, #26) |
| Jackson 3 refuses a missing primitive event field | #21 | Boxed `Long`; FF-13; rule 7 |
| design §8 lacked `request-in-progress`, the key scope and the key format | #23, #24, #27 | Added: 409 with `Retry-After`; keys scoped per account; UUID only |
| Renaming a listener orphans its pending publications | #27 | CLAUDE.md rule 7 (this PR); a Slice 2 input (§4.5) |
| design §8's `GET /v1/access/{subscriberId}` vs the code's `GET /v1/accounts/{id}/access` | #27 | Not a gap: a Slice 2 input (§4.5) |

### 3.3 Bugs found during the slice

| Bug | Root cause | Fix |
|---|---|---|
| Concurrent duplicate invoices failed instead of being absorbed | PostgreSQL absorbs a conflict only on the named arbiter (`invoice_payment_reference_key` was not it) | `ON CONFLICT (id)` plus an advisory lock (#20) |
| Every renewal conflict was absorbed as "already renewed" | The handler did not check which fact the conflict was about | Recheck in a fresh transaction (`RenewalFailureIT`); rule 11's sentence on naming the guarded fact (#20) |
| `InconsistentLookupIT` and `ReconciliationIT` were flaky | Test contexts share one database and the `payment.jobs` row, each on its own clock, so another context's job resolved the attempt first | Direct calls; the shared job parked in the year 9999, then restored after each test (#20, #23) |
| Events stored without `sequence` could not be read | Jackson 3 and primitives | Boxed `Long`; FF-13 (#21) |
| `IdempotencyKeysTest.aRefusedRequestIsNotSavedSoTheSameKeyChargesLater` failed in CI (run 38129514524) | Rate-limit window per wall-clock second from `System.currentTimeMillis()`; two requests could straddle a second | `Clock` bean, `TestClock`; failure reproduced first (#25) |
| AT-16 once got `1=SUCCEEDED, 2=SUCCEEDED` | The reference lookup shows every attempt's charges; reconciliation booked attempt 2's charge to attempt 1, causing a second `PaymentSucceeded` and a false correction count | Latest attempt only; `ReconciliationIT.aRetrysChargeDoesNotCorrectTheDeclineBeforeIt` reproduces it every time (#26) |

## 4. Open questions and suggested new decision records

### 4.1 Proposed records, as finally built

These are rows 024 to 026 of `docs/architecture/v2/README.md`, "Proposed". They are to be confirmed in the Design Record.

**Proposed ADR-024: an unrecognised enum value is `UNRECOGNISED`** (#13 point 11; built in #20).
- **Rule:** where UNKNOWN is a real state of a shared enum, a value the reader does not know maps to `UNRECOGNISED`, never to `UNKNOWN`. It amends ADR-016.
- **As built:** `AttemptState.UNRECOGNISED` is the `@JsonEnumDefaultValue`. FF-12 (`EnumsHaveUnknownDefaultTest`) accepts it for the enums pinned as `UNKNOWN_IS_A_STATE`, and `UnknownEnumValuesIT` proves it.
- **Wording:** unchanged.

**Proposed ADR-025: a decline on the first invoice ends the subscription at once** (#13 point 8).
- **Not built.** It is for finance and product.
- **What happens instead:** access is granted at sign-up (H7), and a declined first payment goes to Collections like any other decline.

**Proposed ADR-026: how an unknown payment is resolved** (S-2; #20, #22, #26).
- **As built** (row 026, updated in this PR):
  - **(a) Look the reference up.** For the **latest** attempt of the reference, a charge found decides, and a decline found decides an UNKNOWN first attempt. An earlier attempt learns nothing from the lookup, which also shows its successors' charges: a superseded decline stops being re-checked, and an earlier UNKNOWN goes on to (b).
  - **(b) Repeat with the key.** If nothing decides and the attempt's own key is younger than the provider's retention minus a margin (`key-repeat-window`, 20 h), repeat with the key and the stored parameters; the outcome decides.
  - **(c) Never repeat** after that: the attempt stays UNKNOWN, with the alert, for settlement reconciliation (ADR-021).
  - "Not found" alone never means "no charge".
  - Only a SENT attempt becomes UNKNOWN by age; a REQUESTED attempt was never sent.
  - Before Collections retries a declined reference, Payment looks the reference up. If the provider shows any charge for it, no new attempt is made, and the declined attempt is corrected to SUCCEEDED (`collectAgain`).
- **Wording the code changed:**
  1. **#20:** "a requested or sent attempt older than the timeout" became **SENT only**. ADR-018's text was left untouched, and the rule moved here.
  2. **#22:** the consistent-not-found rule (`UNKNOWN → TECHNICAL_FAILURE` when the lookup was trusted) was **removed**, with its settings.
  3. **#22:** "a decline found decides" became "decides **only the first attempt**".
  4. **#26:** "a charge found decides" became "decides **only for the latest attempt**". Row 026 lacked changes 3 and 4 until this PR.
- **Contract assumptions** (CONTRACT.md):
  - **A11:** the lookup reflects a charge long before Collections' 3-day retry delay.
  - **A23:** the real fix is the attempt number in each charge's metadata, which gives an exact match. Once a provider confirms it, both "first attempt only" and "latest attempt only" can be replaced by that match.

### 4.2 Suggested new decision records

1. **UUID-only idempotency keys** (#27). An extension of ADR-019: every `Idempotency-Key` is a UUID, or `400`, not stored. Loosening the rule later is additive; tightening it would break clients.
2. **Idempotency keys in `platform`** (#24). An extension of FR-027, already in CLAUDE.md rule 1:
   - one key table per module;
   - claimed before acting;
   - the response stored in the same transaction as the change;
   - a 60 s takeover;
   - `request-in-progress` with `Retry-After`.
3. **Pinned listeners** (#27; CLAUDE.md rule 7 in this PR). An extension of ADR-016: a listener is pinned like an event class, and moving one means deploying the new one first, then draining the old one.
4. **An F-number for `AccessReactsOnlyToEventsTest`** (§5), and for the closed-layer domain rule if `domainDoesNotDependOnAdapters` is split.

### 4.3 Must-haves before production

1. **Authentication, with idempotency keys scoped per caller.** The whole API is unauthenticated today:
   - `POST /v1/accounts` keys share the scope `unauthenticated`;
   - sign-up keys are scoped by the account in the body.
2. **Settlement reconciliation** (proposed ADR-021). It is the only backstop for an UNKNOWN attempt past the key window, and for the double charge in §4.4.
3. **Commands to record a decision on an UNKNOWN attempt past the key window.** `docs/runbooks/unknown-payment-past-key-window.md` describes the decision; without a command, recording it means editing the database by hand.

### 4.4 Known limits

1. **One Collections case per account.** A second declined invoice gets no retry of its own while a case is open. `InvoicePaid` closes the case only for the invoice it opened for. Input to the Collections slice (#22): a case covers every unpaid invoice of the account, retries each, and closes only when none is unpaid.
2. **A false decline with a lagging lookup at the day-3 retry would charge twice.** If the provider reports a decline but charged, and its lookup still misses that charge when `collectAgain` looks before retrying, a second attempt charges again. The 3-day delay makes this unlikely (CONTRACT.md A11). Settlement reconciliation (ADR-021) is the backstop, and is required before production.
3. **`AccountStanding.set` has no caller.** F3 guards it, but Collections does not change standing in Slice 1R, and no `StandingChanged` event exists.
4. **The Subscription state machine of design §5 is not built** (TRIAL, ACTIVE, CANCELLING, ENDED). The brief's PR-5 moved only the calendar and `renew(n)`. See §6, ADR-018.
5. **AT-4 does not assert technical failures directly** (§1).
6. **Offers:**
   - `OfferVersionPublished` is not built;
   - `free-trial` is seeded as EXPERIMENTAL;
   - an "approve a kind" command, when built, must refuse a kind with no rule (#18 point 4).

### 4.5 Slice 2 inputs

1. **The access endpoint's shape.** design.md §8 specifies `GET /v1/access/{subscriberId}`, answering `canWatch, reason, asOf, sequence`. Slice 1R serves `GET /v1/accounts/{id}/access`, answering `{accountId, hasAccess}`. `asOf` and `sequence` come from the access-facts topic (ADR-012), so the endpoint belongs to Slice 2, when Access becomes its own deployable. The old path stays beside it until then (#13 answer 9).
2. **Listeners are pinned.** Access's listeners move to a new deployable in Slice 2. Spring Modulith stores each pending publication with its listener's identity, so the new listener is deployed first, and the old one is removed only once it has no incomplete publications (CLAUDE.md rule 7).
3. **A missing sequence means "order not known"**, never "older". Events stored before #21, and events from the previous release during a rolling deploy, carry no sequence (rule 7, FF-13). Access's consumer of the access-facts topic must treat it so.
4. **The temporary edge** `access → subscription, account` (events only) goes away when Access reads the access-facts topic.
5. **Alert rules** (Slice 2, step 0). Today the alert links live in meter descriptions.

### 4.6 Ports still using Spring Data names

**None** in `offers`, `subscription`, `invoicing` or `payment`, the modules rule 14 covers. Every domain port method has a business name:
- `Invoices`: `addIfAbsent`, `forPaymentReference`, `issuedBetween`.
- `PaymentAttempts`: `dueForReconciliation`, `sentBefore`, `latestFor`.
- `Subscriptions`: `add`, `find`, `existsFor`.

Each `Jpa*` adapter extends the port and keeps Spring Data names behind default methods.

Outside rule 14 (closed layers, by design):
- `account.domain.AccountRepository` extends `JpaRepository`.
- `collections.domain.CollectionCases` exposes `findByAccountIdAndInvoiceIdAndStatus` and `findByAccountIdOrderByOpenedAtDesc`, beside its business-named `openIfNone` and `recordRetry`.

**Fixed in this PR:** rule 14 cited `JpaInvoices.insertIfAbsent` as its example, which does not exist. It now cites `JpaCollectionRequests.addIfAbsent`.

### 4.7 Open questions and later work

1. ~~Should `domainDoesNotDependOnAdapters` be split into F9 plus a closed-layer domain rule (§5)?~~ Done at the close-out: F9 bans `jakarta.servlet`, new F11 covers the closed-layer domains, and FF-8 is retired (§7).
2. Should the proposed records 024 and 026, and the suggestions in §4.2, be confirmed in the Design Record as they stand?
3. **Later work, required before the first production database:** a migration check that rejects destructive DDL (dropping or renaming a schema, table or column, or narrowing a type) unless the migration carries an explicit marker. Today nothing checks migrations (§6, ADR-009), and this slice's one-step renames were allowed only because no production database exists. No code now.

## 5. Fitness functions added

**Required by the brief:**

| Function | Test | Rule |
|---|---|---|
| F3 | `architecture.F3StandingTest.onlyCollectionsSetsAnAccountsStanding` | Nothing outside `account` and `collections` may depend on `AccountStanding` |
| F4 | `architecture.SharedKernelTest.sharedHoldsOnlyMoneyRatioAndTheEventMarker` | `shared` holds only `Money`, `Ratio`, `DomainEvent`; the list is pinned |
| F8 | `architecture.F8InwardDependenciesTest`: `theDomainDependsOnNeitherTheApplicationNorAnAdapter`, `theApplicationDoesNotDependOnAnAdapter` (each run for offers, subscription, invoicing, payment) | Dependencies point inward |
| F9 | `architecture.F9PlainDomainTest.aDomainClassImportsNoSpringAndNoAdapter` (the same four modules) | The domain imports no Spring and no adapter; `jakarta.persistence` is allowed |
| F10 | `architecture.F10ClosedLayersTest`: `accountIsBuiltInClosedLayers`, `collectionsIsBuiltInClosedLayers` | Closed layers in `account` (web → application → domain) and `collections` (application → domain) |

**Added beyond the brief:**

| Function | Test | Rule |
|---|---|---|
| FF-13 | `architecture.EventFieldsMayBeMissingTest.everyEventFieldAddedLaterIsAReferenceType`; runtime proof in `platform.internal.EventEnvelopeIT.anEventStoredWithoutASequenceStillReads` | Every event field added later is a reference type, so an event stored before the field existed still reads |
| Access events only (no F-number yet) | `architecture.AccessReactsOnlyToEventsTest.accessUsesOnlyTheEventsOfSubscriptionAndAccount` | `access` may use `subscription` and `account` only through their `DomainEvent` classes and the types those events carry: never a billing API. Shown failing first with a temporary `SubscriptionApi` field (#27) |
| Contract coverage | `contract.ContractIT` (10 tests) | Every operation in `openapi.yaml` is tested and every served `/v1` handler is specified, so no endpoint ships undocumented. Every state-changing operation requires a UUID key |
| Pinned graph | `architecture.PinnedModuleDependenciesTest.declaredDependenciesMatchTheApprovedGraph` | Now checks `module-dependencies.txt`, design §3 itself (#27) |

**All first-run fitness functions still exist:**
- FF-1, FF-2: `ModuleStructureTest`, now listing nine modules;
- FF-3 to FF-8 and FF-10: `ArchitectureRulesTest`;
- FF-9: `PinnedEventClassesTest`;
- FF-10b: `EveryJobHasHealthMetricsIT`;
- FF-11: `PinnedModuleDependenciesTest`;
- FF-12: `EnumsHaveUnknownDefaultTest`, now with proposed ADR-024.

**Two numbering schemes collide:** the first run's FF-8 is `domainDoesNotDependOnAdapters`, while the second design's F8 is `F8InwardDependenciesTest`.

**Can `ArchitectureRulesTest.domainDoesNotDependOnAdapters` be retired? Not yet.**
- **Its rule:** no class in `com.tally.*.domain..` may depend on `com.tally.*.adapter..`, `org.springframework.web..`, `org.springframework.http..` or `jakarta.servlet..`.
- **For the four ports-and-adapters modules,** F9 covers it, except for `jakarta.servlet..`, which F9 does not ban.
- **For `account` and `collections`,** F8 and F9 do not apply. F10 stops their domain using their own web layer, but nothing else stops `account.domain` or `collections.domain` from using `org.springframework.web`, `org.springframework.http` or `jakarta.servlet`. Nothing stops `collections.domain` from using a future `collections.adapter`.
- **Why F9 can't simply cover them:** both domains legitimately import Spring Data (closed layers).
- **To retire it:** add `jakarta.servlet..` to F9, and add a closed-layer domain rule for `account` and `collections` that bans web, http, servlet and adapters but allows Spring Data. That is a candidate for its own F-number.

## 6. Audit of the decision records this slice touched

The "Checked by" column of `docs/architecture/v2/README.md`, against the code. A check counts only if a test exists; planned checks are listed as gaps.

| Record | Compliance says | Check in the code | Gap |
|---|---|---|---|
| ADR-001 (modular monolith; Access a separate quantum) | Pinned graph; Access-survives-billing test (Slice 2) | `PinnedModuleDependenciesTest`; `AccessReactsOnlyToEventsTest` | **The Access-survives-billing test does not exist**; it is planned for Slice 2 |
| §10 (module rules) | F1, F2 | `ModuleStructureTest` (FF-1, FF-2); `ArchitectureRulesTest.paymentDoesNotDependOnInvoicing` | **No tests named F1 or F2.** The README says payment depends on `shared` only; design §3 and `payment/package-info.java` allow `shared, platform` |
| §10 C (inside layout) | F8, F9, F10 | The three tests (§5) | `jakarta.servlet` and the closed-layer domains (§5) |
| §12 (aggregates) | Aggregate tests | `SubscriptionTest`, `InvoiceTest`, `OfferTest`, `PaymentAttemptTransitionsTest` | None for the aggregates built |
| ADR-002 | AT-7, AT-8 | Both pass | None |
| ADR-003 (scheduled work on queues) | AT-9 | AT-9; `ConcurrentRenewalIT`; `SubscriptionTest.renewingThePeriodAgainChangesNothing` | None |
| ADR-004 (no transaction during the provider call) | AT-5 | AT-5, AT-15, `UnknownResolutionIT` | None |
| ADR-005 (Collections) | State-machine tests | AT-16 only | **No Collections state-machine test.** `CollectionCase` has only OPEN and CLOSED, with no transition table |
| ADR-008 (trunk, pipeline) | Pipeline configuration | `.github/workflows/ci.yml`: `commit-stage`, `acceptance-stage`; `nightly.yml` | **No staging or production stages** |
| ADR-009 (expand and contract) | Migration check; N−1 replay | N−1 event reads: FF-13, `EventEnvelopeIT`. Previous-release data: `BilledForBackfillIT`, `CollectionRequestBackfillIT` | **No general migration check** (nothing rejects destructive DDL). The slice's one-step renames were allowed by the brief (§3.1) |
| ADR-013 (technical failures) | AT-4, transition test | AT-4; `PaymentAttemptTransitionsTest.everyStateWithEveryEvent` | AT-4 does not assert `TECHNICAL_FAILURE` directly |
| ADR-014 (5-second access promise) | AT-1 | AT-1 | None |
| ADR-015 (offers, pricing) | Start-up check; golden tests | `PricingRulesStartupCheckIT.refusesToStartWhileAnApprovedKindHasNoRule`; `FixedPricePhaseRuleGoldenTest`, `FreeTrialRuleGoldenTest` (each example quoted twice, O3) | None |
| ADR-016 (events) | FF-12; AT-17 | `EnumsHaveUnknownDefaultTest`, AT-17, `EventSequenceIT`, FF-13, `SpikeS1ResubmitOneAtATimeIT` | **No test pins listener identities** (rule 7's new sentence) |
| ADR-017 (Money) | Golden tests; F4 | `MoneyTest` (golden examples; property test `allocationAlwaysSumsToTheWhole`), `RatioTest`, `SharedKernelTest` | None |
| ADR-018 (state machines) | Exhaustive table tests; AT-15 | `PaymentAttemptTransitionsTest`; AT-15 | **No table test for Subscription, Invoice or Collections.** The Subscription machine of design §5 is not built (§4.4) |
| ADR-019 (REST, idempotency) | AT-13; contract tests | AT-13; `ContractIT` (10), including `everyStateChangingOperationRequiresAUuidKey` and `aKeyThatIsNotAUuidIsRefusedAndNotStored` | None |
| FR-040 (pinned graph) | A test checks the approved graph | `PinnedModuleDependenciesTest`, on design §3 since #27 | None |
| Proposed ADR-024 | (not yet in the column) | `EnumsHaveUnknownDefaultTest`, `UnknownEnumValuesIT` | The column has no entry for proposed records |
| Proposed ADR-026 | (not yet in the column) | `UnknownResolutionIT`, `KeyRepeatWindowTest`, `PaymentAttemptTransitionsTest.aRequestedAttemptOfAnyAgeIsNeverMadeUnknown`, `ReconciliationIT.aRetrysChargeDoesNotCorrectTheDeclineBeforeIt`, AT-15, AT-16 | As above |

## 7. Close-out (after the report)

These changes were made after the report, in the close-out PR. The user's decision is posted verbatim on #28. Sections 1 to 6 describe the state at the end of the slice; this section records what changed since.

| Item | Change | Test, shown failing first |
|---|---|---|
| AT-4 gap (§1, §6 ADR-013) | AT-4 now asserts that the outage produced `TECHNICAL_FAILURE` attempts and no `DECLINED` ones. The gap is closed. | `AT04ProviderOutageIT`. With the transition table's `SENT + PROVIDER_ERROR` row temporarily pointing to `DECLINED`: `[technical failures] Expecting actual: 0L to be greater than: 0L` |
| F9 and `jakarta.servlet` (§5) | F9 bans `jakarta.servlet..` as well | `F9PlainDomainTest`. A temporary `jakarta.servlet.ServletRequest` field in `payment.domain`: `Field <…ReconciliationSchedule.redStepOnly> has type <jakarta.servlet.ServletRequest>` |
| **F11**, closed-layer domains | In `account` and `collections`, the domain may not depend on `org.springframework.web..`, `org.springframework.http..`, `jakarta.servlet..` or any adapter package. Spring Data is allowed. A second test fails if a module with a domain package is checked by neither F9 nor F11. | `F11ClosedLayerDomainTest`: `aClosedLayerDomainUsesNoWebAndNoAdapter`, `everyDomainPackageIsCoveredByF9OrF11`. A temporary `HttpStatus` field in `account.domain`: `Field <…Account.redStepOnly> has type <org.springframework.http.HttpStatus>` |
| FF-8 retired | `ArchitectureRulesTest.domainDoesNotDependOnAdapters` is removed: F9 and F11 cover every domain package between them, and the coverage test keeps it so | — |
| **F12** | `AccessReactsOnlyToEventsTest` is renamed `F12AccessReactsOnlyToEventsTest` | Unchanged rule (shown failing first in #27) |
| Pinned listeners (§6 ADR-016 gap) | `PinnedListenersTest` pins every event listener's identity, `class.method(parameter types)`, the form Spring Modulith stores with each publication, in `architecture/pinned-listeners.txt` (7 listeners). A rename or move fails the build (rule 7). The gap is closed. | `PinnedListenersTest.eventListenersMatchThePinnedList`. With the pin file empty: `Event listeners missing from architecture/pinned-listeners.txt` |
| README row §10 (§6) | "Payment depends on `shared` only" corrected to `shared` and `platform`, as design §3 and the code say. Row §10 C names F11 among its checks. | — |
| Decisions in the repository | From now on, a decision the user pastes is posted verbatim as a comment on the PR it concerns before it is acted on (CLAUDE.md, "How to work") | — |


### Test-infrastructure debt: one database, many clocks

**Root cause.** Integration tests share one database. Spring caches several test contexts, and each runs its own job runner on its own `TestClock`. A scheduled job's row (`payment.jobs`, `platform.jobs`, `invoicing.jobs`) is shared by all of them. So a context whose clock is ahead can claim the row and reschedule it into its own future, where another context's runner never sees it due. Its run also acts on the shared rows with its own clock.

**Three flakes so far came from it:**

| Flake | PR | What happened | Mitigation |
|---|---|---|---|
| `InconsistentLookupIT` | #20 | Another context's reconciliation job resolved the attempt first | Direct calls instead of the job |
| `ReconciliationIT` | #23 | The shared job changed the counters the test checked | The job parked in the year 9999, restored after each test |
| AT-15 | #32 run 38153130900 | The reconciliation job sat in another context's future; the attempt was never resolved within 15 s | `SharedJob` re-arms the row at the waiting context's clock on every poll (AT-5, AT-6, AT-8, AT-12, AT-15, `EventSequenceIT`, `EventResubmissionIT`, Spike B); `SharedJobIT` reproduces the stranded job |

**The mitigations narrow the race; they do not remove it.** A foreign context that claims the job still runs it on its own clock.

**Candidate fix for Slice 2, step 0, to be spiked:** one test clock shared by every context.
