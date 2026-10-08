# Slice 1c report: closing the slice 1b audit gaps

Scope: the seven pull requests asked for after `tally-docs-update-2`, in the order given. Each is a short-lived branch from `main`, merged by the architect (ADR-038). `./mvnw verify` was green locally before every commit.

## 1. What was built

| PR | Branch | What | Tests | Local |
|---|---|---|---|---|
| [#2](https://github.com/waleedarafa/tally/pull/2) | `claude/docs-update-2` | Docs: ADR-041, compliance-status Notes, review part 3 | none (docs) | n/a |
| [#3](https://github.com/waleedarafa/tally/pull/3) | `claude/ci-pull-request` | CI runs both stages on `pull_request` too; images only for pushes | the PR's own `pull_request` run (run 18) | pass |
| [#4](https://github.com/waleedarafa/tally/pull/4) | `claude/collection-request` | ADR-041: `CollectionRequest` aggregate, V14 with backfill, insert-or-return, attempts linked, new queue guard | `DuplicateCollectionRequestIT`, `CollectionRequestBackfillIT` | pass |
| [#5](https://github.com/waleedarafa/tally/pull/5) | `claude/subscription-listener-duplicates` | Invoicing's `SubscriptionChanged` listener absorbs duplicates (atomic inserts) | `DuplicateSubscriptionDeliveryIT` | pass |
| [#6](https://github.com/waleedarafa/tally/pull/6) | `claude/invoice-billed-for` | `billed_for` on invoices (V15, backfilled); AT-3 asserts it | AT-3, `BilledForBackfillIT` | pass |
| [#7](https://github.com/waleedarafa/tally/pull/7) | `claude/unknown-enum-values` | Rule 7: `UNKNOWN` defaults on five enums; Jackson maps unknown values to them | FF-12, `UnknownEnumValuesIT` | pass |
| [#8](https://github.com/waleedarafa/tally/pull/8) | `claude/money-queues` | Money queues flagged; `money` tag on queue metrics | `MoneyQueuesIT` | pass |

Every new behaviour test was shown failing first:
- `DuplicateCollectionRequestIT` fails with a check-then-insert `requestCollection`. The concurrent duplicate hits the unique constraint, and its delivery never completes.
- `DuplicateSubscriptionDeliveryIT` fails with the previous listener: 1 failed delivery.
- `UnknownEnumValuesIT` fails with the Jackson setting off.
- FF-12 fails when `FailureReason` loses its default (`slice-1-fitness-proof.md`, v13).

All twelve acceptance tests still pass on each branch.

**Merge order.** Three pairs of PRs interact:
- **#4 and #6:** #4 adds migration V14 and #6 adds V15. Merge #4 first. Otherwise, a database that already ran V15 refuses V14 as out of order. This only matters once a shared database exists.
- **#5 and #6:** both edit `InvoiceIssuer.issue`.
- **#4 and #8:** both edit `CollectionQueue.DEFINITION`.

Whichever merges second needs a small conflict resolved, which I'll do on that branch.

## 2. Findings, with evidence

1. **Spring Modulith 2.1.1: resubmission deserializes events in the calling thread.**
   - **Evidence:** with the unknown-enum setting off, `resubmitIncompletePublicationsOlderThan` itself threw `InvalidFormatException` (`UnknownEnumValuesIT`, failing run in PR #7).
   - **Effect:** one unreadable publication fails the whole resubmission job run, not just its own listener. Every publication waiting behind it then waits too.
   - The Jackson setting removes the enum case, but any other deserialization error, such as a removed field type, would do the same. ADR-028 does not mention this.
2. **Spring Modulith 2.1.1: two publications with identical content confuse completion.**
   - **Evidence:** publishing the same event object twice (same event ID) left one publication `PUBLISHED`, with its listener run once and never marked complete. This was in `DuplicateSubscriptionDeliveryIT`'s first version.
   - Completion appears to match on serialized event and listener, not on the publication's ID.
   - A real duplicate (resubmission) re-runs the same row, so production is not affected. Tests must give each delivery its own event ID.
3. **The payment-result listeners are safe, but they do not absorb concurrent duplicates.**
   - `PaymentSucceeded` and `PaymentFailed` are guarded by the invoice's optimistic lock. A concurrent duplicate never pays twice, but it loses with an optimistic-lock error, and it is resubmitted.
   - By the review's reasoning for the subscription listener, every such duplicate becomes a failed publication and, once alerts exist, a false alarm.

## 3. Deviations from the brief or the records, and why

- **`payment_attempt.request_id` and `invoice.billed_for` are nullable** (ADR-041 says each attempt belongs to one request). During a rolling update (ADR-007), the previous release still inserts rows without them. A later release must backfill again and add `NOT NULL`. Until then, `CollectionRequests.settle` and the code reading `billedFor` accept the gap.
- **`FailureReason` and `AttemptState` keep their existing `UNKNOWN`** as the unknown-value default, rather than gaining a second constant. Their meanings ("unresolved, take no action" and "the charge may have happened") are already the safe readings of a value this version doesn't know.
- **A `pull_request` run builds no image.** Its merge commit never reaches `main` under that ID, so the image could never be deployed.
- **FF-12 covers only top-level enums in a module's base package, plus enums stored in events.** `platform.Outcome.Done` is a nested singleton marker and is excluded.

## 4. Open questions and suggested records

- **Q1:** should the payment-result listeners absorb concurrent duplicates too (finding 3)? For example: a conditional update instead of the optimistic lock, or catching the lock failure as "already done".
- **Q2:** pin findings 1 and 2 in `SpikeAEventPublicationIT`, so a Modulith upgrade that changes them fails the build. ADR-028 could add "a poison publication blocks resubmission" to its context. It could also say the job must survive one unreadable row: resubmit with the predicate API, one publication at a time, and catch per row.
- **Q3:** schedule the contract migrations: `request_id NOT NULL` and `billed_for NOT NULL`, in the release after #4 and #6.
- **Q4:** the commit stage now has about 60 s more of tests: `DuplicateCollectionRequestIT` about 25 s, `DuplicateSubscriptionDeliveryIT` about 18 s, `UnknownEnumValuesIT` about 15 s. It should stay under ADR-038's 5 minutes, which is to be confirmed on CI after the merges.

## 5. Fitness functions added

| ID | Rule | Test | Guards |
|---|---|---|---|
| FF-12 | Every cross-module or event enum has `UNKNOWN` marked `@JsonEnumDefaultValue`; the list is pinned | `architecture.EnumsHaveUnknownDefaultTest` | `CLAUDE.md` rule 7 |
| (check) | Every queue's duplicate guard exists as a unique constraint on a business table; the payment queue now names `collection_request_one_per_reference` | `DuplicateGuardCheck`, at registration | ADR-037, ADR-041 |
| (pin) | The money queues are exactly `billing-schedule` and `payment-collection`, and are tagged in metrics | `platform.internal.MoneyQueuesIT` | ADR-037 |

## 6. Audit of the records this work touched, against the code

Read as: the code on `main` once PRs #2 to #8 are merged. "Exists" means a test or check in the repository runs it; planned checks are listed as gaps.

### ADR-028. Event resubmission rules
| Decision or compliance item | Status |
|---|---|
| "Older than" API only | Exists: `EventResubmission` |
| Job runs through the durable queue | Exists: periodic job in `platform.jobs`; `PeriodicJobIT.twoInstancesRunADueJobOnce` |
| Minimum age 5 minutes | Exists: `tally.events.resubmit-older-than` default `5m` |
| `republish-outstanding-events-on-restart` off | Exists: default, not set anywhere |
| Every listener safe under concurrent duplicates | **Partly.** Absorbed atomically: invoicing's `SubscriptionChanged` (#5), invoicing's `InvoiceIssued` → `requestCollection` (#4), and entitlement's grants (`ON CONFLICT`). Safe but failing on a concurrent duplicate: invoicing's `PaymentSucceeded` and `PaymentFailed` (optimistic lock; finding 3, Q1) |
| Alert on `oldest_incomplete_age_seconds` above 15 minutes | **Gap.** Only the metric exists; alert rules come with staging (slice 2, step 0), as the record's Notes say |
| Compliance: `SpikeAEventPublicationIT` pins library behaviour | Exists, but it does not pin findings 1 and 2 (Q2) |
| Compliance: `ConcurrentDeliveryIT` | Exists. It checks the effect (one `InvoicePaid`), not that the duplicate delivery succeeds |
| Compliance: a fitness function delivering every pinned event twice at once | **Planned for slice 2**, not claimed. Two of the six pinned events now have such a test (#4, #5) |

### ADR-037. Retention of finished work
| Decision or compliance item | Status |
|---|---|
| Hourly retention job through the platform's job queue, 30 days | Exists: `Retention`, `RetentionIT` |
| Failed rows never deleted | Exists: only `DONE` rows are deleted (`RetentionIT`) |
| Failed row in a money queue raises an alert | **Partly.** Money queues are flagged and tagged (#8). **No alert rule exists**; it comes with staging |
| Business guard per queue, never deleted | Exists for both queues: `invoice_one_per_account_and_period`, and `collection_request_one_per_reference` (#4, which replaces the partial index). Nothing deletes from either table |
| Compliance: retention job reports health | Exists: `tally.job.*{job="retention"}` |
| Compliance: manual review that each queue names its guard | Exceeded: `DuplicateGuardCheck` refuses registration without a real unique constraint on another table (`DurableQueueIT`) |

### ADR-038. The pipeline has two stages
| Decision or compliance item | Status |
|---|---|
| Commit stage on every push and pull request; under 5 minutes | Exists after #3. Measured on CI at about 2 min 50 s (run 18) before PRs #4 to #8 added about 60 s |
| Acceptance stage: AT-1 to AT-12, spikes, chaos tests; tags `accepted-<commit>` | Exists. Tagging happens on push runs only |
| Only `accepted-*` images may be deployed; deployment jobs accept only them | **Gap.** No deployment job exists; it comes with staging, as the Notes say |
| `main` is the trunk; short-lived branches merged by pull request | Followed for this work (PRs #2 to #8). Nothing enforces it in the repository, such as branch protection |
| `./mvnw verify` runs everything locally | Exists: no profile means both stages |

### ADR-041. One collection request per reference
| Decision or compliance item | Status |
|---|---|
| One `CollectionRequest` per reference, full unique constraint, never deleted | Exists (#4): `collection_request_one_per_reference`; nothing deletes requests |
| `requestCollection` is an atomic insert-or-return; signature unchanged | Exists (#4): `INSERT ... ON CONFLICT DO NOTHING` |
| Each attempt belongs to one request | **Partly.** `request_id` is set for every new attempt and backfilled, but nullable until the contract migration (Q3) |
| Partial index stays as a second guard | Exists |
| Payment queue names the new constraint | Exists (#4); checked at registration |
| The request holds the overall outcome | Exists (#4): `outcome` follows the attempt in the same transaction, in `CollectionWorker` and `Reconciliation` |
| Compliance: `InvoiceIssued` twice, at once and after the attempt finished: one request, one attempt, one charge | Exists: `DuplicateCollectionRequestIT`, shown failing with check-then-insert |
