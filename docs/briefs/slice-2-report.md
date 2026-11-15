# Slice 2 report: Access as its own deployable

Slice 2 made Access a second deployable (ADR-001). It ran from PR #61 to PR #72, all merged. Record numbers follow CLAUDE.md: `ADR-NNN` for the second design, `FR-NNN` for the first run, `Dn` for drivers.

**The brief's goal, and where it stands:**

| When this slice is done | State | Evidence |
|---|---|---|
| Access runs as its own process, with its own PostgreSQL | Yes | `access/` (`com.tallyaccess`), its own Flyway schema, image `tally-access:<commit>`, pipeline `access.yml` (#69) |
| Billing sends the current access facts of each account to an event log | Yes | `accessfeed` (#66) and its relay (#68), topic `access-facts` |
| Access reads the log and answers from its own copy | Yes | `GET /v1/access/{subscriberId}` (#69) |
| A test stops every billing copy and the billing database; Access still answers | Yes | AT-18 (#70) |
| A test empties Access's database; Access builds its copy again from the log | Yes | AT-19 (#70) |
| Step 0b: the nightly run passes again, twice by hand, then the schedule is enabled | **Not finished** | The cause is found and fixed (#61): AT-10 ran with 10,000 renewals for the first time, three times. The workflow `Nightly load` is still `disabled_manually`, so it cannot be dispatched, and no tool here enables it (§3.1) |

**Builds at the end of the slice** (`main` at 2f60140): `./mvnw verify`, JDK 25, BUILD SUCCESS. Fake provider 20 tests; Access 15 unit and 19 integration tests; billing 150 unit and 217 integration tests. CI green on every merged head: billing's commit stage (with promtool), Access's pipeline, billing's acceptance stage (53 acceptance and spike tests on #70).

**Where the user's decisions are recorded.** Each decision given in chat is posted verbatim as a comment on the pull request it concerns, before it was acted on (CLAUDE.md, "How to work"). The architecture reviews are comments on the same pull requests. This report cites them by pull request.

## 1. What was built, per acceptance test

| AT | Status | Test | What it shows | Result |
|---|---|---|---|---|
| AT-1 | changed | `AT01SignUpHappyPathIT.signUpPromisesAccessWithinFiveSecondsThenTheInvoiceIsPaidAndAccessGranted` | After `POST /v1/subscriptions`, the **Access service**, a container built from its jar, answers `canWatch: true`, reason `ACTIVE_SOURCE`, within 5 s. Until PR-9 it also asked the old endpoint; since PR-9 it asks only Access. Printed times: 732 ms locally, 769 ms in CI (#70) | pass |
| AT-18 | new | `AT18AccessSurvivesBillingIT.accessAnswersEveryAccountWhileBillingAndItsDatabaseAreStoppedAndASignUpReachesItAfterwards` | 100 sign-ups through two billing processes. Both are killed, and the billing database (a container of its own) is stopped. Access answers all 100 as before, and 20 unknown IDs `NOT_KNOWN`. Database and one billing process started again; a new sign-up reaches Access | pass |
| AT-19 | new | `AT19RebuildIT`: `aRebuildOnATopicWhereCompactionHasNotRunEndsOnTheSameAnswers`, `aRebuildOnATopicWhereCompactionHasRunEndsOnTheSameAnswers`, `anInstanceStoppedInTheMiddleOfALoadLosesNoMessage` | 300 accounts, 3 messages each, on a 3-partition topic. Access stopped, its database emptied, started again with its **old** consumer group: no reset, no other broker step. Every answer after the rebuild equals the one before. Compacted run: `compaction ran: 303 of 903 messages left`. Two instances: one stopped after 300 of 900 sends; the other applies all 300 accounts at sequence 3 | pass (3) |
| AT-20 | new | `AT20BlockedAndRestoredIT.blockedTakesAccessAwayAndGoodRestoresItWithAHigherSequenceEachTime` | `AccountStanding.set(BLOCKED)`: `canWatch: false`, `ACCOUNT_BLOCKED`; `GOOD` restores `ACTIVE_SOURCE`. Sequences `1 (signed up), 2 (blocked), 3 (restored)` | pass |
| AT-21 | new | `AT21OldAndRepeatedMessagesIT.sixThenFiveThenSixAgainStaysOnTheFirstSix` | Sequence 6, then 5, then 6 again with other facts. The answer stays on the first 6. With Access's sequence guard removed, it fails (`canWatch=false (expected: true)`) | pass |
| AT-2 to AT-17 | kept | as in the Slice 1R report | Unchanged; they pass in every acceptance stage of the slice | pass |

Each new acceptance test was written first, against a test harness whose every method threw `UnsupportedOperationException("not built yet")`, and run red: 7 of 7 failed there (#70).

**"Not ready until caught up": which test proves which half** (decided on #70):
- **Ready comes only after the copy has caught up: AT-19.** When `ready()` turns true, the test compares every answer at once, with no wait; an early ready would show a `NOT_KNOWN` or an older answer, and fail. In CI the rebuilt instance was ready before its port opened, so that run saw no 503 (locally: 77 and 35); the 503 count is printed, not asserted.
- **Not ready gives 503: `AccessStartWithoutLogIT`**, for the endpoint and for the readiness probe, on every run: an emptied database with the log down answers 503, and so does a database whose "complete once" mark was left behind (`aMarkLeftBehindByAnIncompleteEmptyingIsIgnored`).

**Other behaviour built**, by pull request:

| PR | Brief step | Built |
|---|---|---|
| #61 | PR-0 (step 0b) | The cause of the 19 failed nightly runs, and the fix in both Dockerfiles, with a guard; it serves the Access image (§3.3) |
| #62 | Spikes | S-1 to S-3 (§2) |
| #63 | PR-1 | The brief adopted in `README.md` (proposed 033, 034), `design.md` and CLAUDE.md |
| #64 | after S-3 | Proposed ADR-035: Access keeps its position in its own database (brief decision 5) |
| #65 | PR-2 | `StandingChanged`, pinned, published in `AccountStanding.set`'s transaction only when the standing changes; the account's version is its sequence |
| #66 | PR-3 | `accessfeed`: one row of facts per account, sequence +1 only on change; both listeners pinned; F12 extended |
| #67 | PR-4 | `contracts/access-facts/v1/`; F7 (billing side) and F15 |
| #68 | PR-5 | The relay on the platform queue, one Kafka transaction per send; spike S-4; the log in tests and in `infra/docker-compose.yml` |
| #69 | PR-6 | The Access service: Maven module, database, consumer, endpoint, OpenAPI file, image, pipeline; proposed ADR-036 |
| #70 | PR-7 | AT-1 changed, AT-18 to AT-21; billing's acceptance stage builds Access; a readiness bug fixed (§3.3) |
| #71 | PR-8 | Five alerts with promtool tests and runbooks; the lag metric; the mark guard; readiness as a health indicator; F14's Access side |
| #72 | PR-9 | A metric and an alert for a publication whose listener is gone; V35, the ADR-028 check; the in-process `access` module removed; V36 drops its schema |

## 2. Spike findings

Evidence: `docs/briefs/slice-2-spikes.md`, with test names and logs. Each finding is pinned by a test, so a broker, client or library upgrade that changes it fails the build.

| Spike | Finding | Test |
|---|---|---|
| S-1 | `kafka-clients` 4.2.1 and `testcontainers-kafka` 2.0.5, both from Spring Boot 4.1.1's dependency management; broker image `apache/kafka:4.2.1`; runs on Java 25. The reference pages could not be reached from the session's network; the versions come from the published BOMs and jars | `SpikeS2CompactionIT` (S-1 case) |
| S-2 | Before compaction a reader from the start sees every message; after it, only the last of each key, for closed segments only. Five topic settings and the cleaner's back-off make it run within about 2 s. So a rebuild must apply by sequence, as ADR-012 assumes | `SpikeS2CompactionIT` (3) |
| S-3 | An emptied database with the old consumer group does **not** read from the start; the reset needs every instance stopped, and the tool exits 0 when it refuses. This led to proposed ADR-035 | `SpikeS3EmptyDatabaseOldGroupIT` (2) |
| S-4 (found building PR-5) | A send that timed out can still reach the log later, after a newer one from another instance. In a Kafka transaction that is never committed, a `read_committed` reader never sees it. This changed ADR-033: one transaction per send, Access reads committed only (decided on #68) | `SpikeS4UnacknowledgedSendIT` (3) |
| Spring Modulith (PR-9) | A publication whose listener this release does not have: resubmission marks it `FAILED`, leaves it incomplete, and never runs it again. Before, this was an assumption | `ListenerGoneIT` |
| Spring Boot (PR-7) | Spring Boot publishes "accepting traffic" itself once the application has started, which replaced the "refusing" Access had published while its copy was behind | `AccessStartWithoutLogIT` (the probe assertions) |

None of these stopped the slice: each was decided on its pull request, and ADR-033 and the proposed 035 and 036 record the outcome.

## 3. Deviations from the brief or the decision records, and why

### 3.1 Deviations

1. **Step 0b is not finished** (#61). The fix is merged, and the nightly command passed three times on the branch, at nightly size, on Docker 28 and 29. The brief's two `workflow_dispatch` runs on `main` and the schedule wait for the workflow to be enabled (Actions → Nightly load → Enable workflow); it is `disabled_manually`, and this session has no tool that enables a workflow.
2. **ADR-033 gained transactions** (#68, the user's decision after S-4). The brief's property ("one send at a time per account") is kept and is not enough on its own. Four conditions came with it from the review: the ADR-033 text, the contract's README with an Access test, the AT-1 time, and a test that drives the gap.
3. **"The broker stopped and started" is `docker pause` and `unpause`** in `AccessFeedOrderIT` and the relay's tests (#68). The broker keeps its data and its port and stops answering, as when it hangs.
4. **No test drives the gap through two relays with a proxy** (#68, review question 3: the user decided no proxy test). The gap is driven deterministically in `SpikeS4UnacknowledgedSendIT`, and through one relay in `AccessFactsRelayIT.aSendThatTimedOutNeverEndsTheCommittedLogBeforeANewerOne`.
5. **Proposed ADR-036 was added in PR-6** (#69), beyond the brief's readiness rule, which said only "not ready until caught up": a "complete once" mark, so a restart while the log is down still answers, and a liveness rule that a failure of the log or the database never breaks liveness. Both were chosen by the user on #69.
6. **PR-6's first tests were written with the code, not run red first** (#69); mutation checks stood in for that. Every fix after the reviews was test first.
7. **AT-19 and AT-21 write to the log themselves**, as billing's relay writes (one transaction each), not through billing (#70). Billing cannot send an older sequence (AT-21) or a long history quickly (AT-19, which compaction needs).
8. **AT-19 empties three tables, not the brief's two**: the facts, the positions and the ADR-036 mark (#70).
9. **A fifth alert, `AccessReadingFailing`** (#71), beyond the brief's four: the alert ADR-036 called for in place of a broken liveness. It shares `access-stale.md`.
10. **F14 "also scrapes Access" through a test of Access's own** (#71): `F14AccessMetricsIT` in the `access` module checks every `tally_access_` metric the rules read against a scrape of Access; billing's `F14AlertRulesAgreeIT` checks every other `tally_` metric against billing. So each pipeline checks its own deployable's metrics, and Access's pipeline now also runs on `ops/alerts/**`.
11. **Readiness is a health indicator in the readiness group** (#71, from the #70 review), not Spring Boot's availability events: `copyReady` in `management.endpoint.health.group.readiness.include`. The listener that answered Spring Boot's "accepting traffic" is gone.
12. **ADR-028's check is a migration** (#72): V35 stops the deploy while the old listener has an incomplete publication. No production database exists to inspect by hand.
13. **V36 drops the `access` schema in the same release that removes the module** (#72), with a `-- contract:` line: no production database exists, as for the Slice 1R renames. With one, the drop would break the previous release during a rolling deploy (§4.1, point 1).
14. **#72 was squash-merged** (the user's choice, option C on #72). One of its commits held the V35 check and also the module's deletion, which I had staged earlier, and was not green on its own; splitting it needed a force-push that the session's permissions refused.
15. **A process slip** (#61): 03a8538 was pushed before a full verify. It was recorded on the pull request and reverted.

### 3.2 Where the code disagreed with the design, and what was done

| Disagreement | Evidence | What was done |
|---|---|---|
| "One send at a time per account" does not keep an older sequence from landing last | S-4 (#68) | One Kafka transaction per send; Access reads committed only; ADR-033's row names what the managed log must support |
| The broker's stored position does not rebuild an emptied database | S-3 (#62) | Proposed ADR-035: the position in Access's database (#64, built in #69) |
| "Not ready until caught up at every start" leaves no answer when Access restarts while the log is down | #69 review 1, finding 2 | Proposed ADR-036: the "complete once" mark |
| A liveness rule that breaks when the log is down restarts every instance in a loop | #69 review 2, finding 3 | ADR-036: liveness breaks only when the reading thread ends; `tally.access.reading.failing.seconds` and `AccessReadingFailing` instead |
| Spring Boot's own "accepting traffic" overrode Access's readiness | AT-19 (#70) | The readiness probe now follows the copy (§3.3) |

### 3.3 Bugs found during the slice

| Bug | Root cause | Fix |
|---|---|---|
| 19 nightly runs failed: AT-10 never ran with 10,000 renewals | On Docker 28 with the classic image store, the legacy builder (Testcontainers) fails the first build when a `COPY` adds an empty layer; both Dockerfiles copied the empty `snapshot-dependencies` | The empty `COPY` removed, with a guard that fails the build if the layer ever holds a file; shown failing and passing on Docker 28 (#61) |
| The relay sent sequence 1 twice | A send that timed out reached the log after all (S-4, case 1) | Transactions (#68) |
| One failure ended Access's reading for good | The consumer loop let an exception end the thread | It waits, goes back to its stored position, and reads again; `AccessRecoveryIT.aFailedApplyIsReadAgainAndNoMessageIsSkipped` (#69, review 1 finding 1) |
| The readiness probe said UP while the endpoint refused | Spring Boot publishes "accepting traffic" at start | Found by AT-19; fixed test first in #70, then made a health indicator in #71 |
| A mark left behind would let a rebuilt instance answer `NOT_KNOWN` | An operator who empties two of the three tables | The mark counts only with a stored position for the topic; `aMarkLeftBehindByAnIncompleteEmptyingIsIgnored` (#71) |
| The lag gauge dropped its notes after a failed apply | It kept notes for the partitions in the last-applied map, which a failure resets to empty | Notes are kept for the partitions the instance holds; caught by `AccessRecoveryIT.messagesTheLogHoldsAndAccessHasNotAppliedShowInTheirAge` before the push (#71) |

## 4. Open questions and suggested new decision records

### 4.1 From the reviews of PR-6 to PR-9

**PR-6 (#69):**
- Review 1, finding 1 (a failure ended the reading): fixed. Finding 2 (a restart while the log is down): option B, the mark (ADR-036).
- Review 2, finding 3 (liveness): option B (ADR-036). The mark guard and the runbook step went to PR-8 and are built.
- Carried to PR-7 and built: both pipelines build Access, and billing's acceptance stage runs on `access/**` changes. Carried to PR-8 and built: a "not reading" measure for `AccessStale`.

**PR-7 (#70):** leave AT-19 as it is; this report says which test proves which half (§1). The health indicator in place of the listener: built in PR-8.

**PR-8 (#71), three notes:**
1. **A running instance now sees a log outage.** The lag metric asks the broker for the end of each partition on every pass; when the log is down that call fails, so `AccessReadingFailing` fires for running instances too. This closes a gap from #69 review 2 (a running instance saw no failure). **No test covers it.**
2. **A known cost:** one more broker call (`endOffsets`) on every pass, every 500 ms for each instance. Cheap at this size.
3. **Open question: the rebuild has downtime.** The runbook stops every instance, and they answer 503 until the copy has caught up. D13 asks for 99.9%. A rebuild with no downtime (a second copy, then a switch) is out of scope for this slice.

**PR-9 (#72):**
1. **A rule for later removals:** the release that removes a listener does not drop its tables; the drop comes one release later (ADR-009). This slice dropped the schema in the same release only because no production database exists (§3.1, point 13).
2. **V35 checks one moment in time,** when the migration runs. `EventListenerGone` covers the time after it: an old instance that dies mid-rollout leaves a publication that no later release runs.
3. **Suggestion:** ADR-028's "Checked by" column names both checks (§6).

### 4.2 Proposed records, as built

Rows 033 to 036 of `docs/architecture/v2/README.md`, under "Proposed", to be confirmed in the Design Record:
- **033, the access feed:** built as written, including the transaction rule from S-4 and its known limits.
- **034, the contract:** built as written.
- **035, the position in Access's database:** built as written.
- **036, "complete once" and liveness:** built as written. The readiness probe is a health indicator (`copyReady`) since #71, which the row does not need to say.

### 4.3 Suggested new decision records

1. **A listener's tables are dropped one release after the listener** (an extension of ADR-009 and ADR-028; §4.1, PR-9 point 1).
2. **A publication whose listener is gone is an alert** (`tally.events.listener_gone`, `EventListenerGone`; an extension of ADR-028 and FR-022).
3. **The managed event log:** which service, against the requirements ADR-033 names (transactions, `read_committed`, the refusal of a late write after an abort; S-4 experiment 3 is the test). Out of scope in this slice, with a deployed environment and SLOs for D13 (WAR-70, WAR-71, WAR-75).
4. **A rebuild with no downtime**, if D13 requires one (§4.1, PR-8 point 3).

### 4.4 Known limits

1. **A late committed reader hold-back:** an instance that dies between a send and its commit holds `read_committed` readers of that partition back for up to the transaction timeout (15 s), once (ADR-033). No test measures it.
2. **One account per transaction:** an instance sends a few hundred accounts a second at most, enough for sign-ups and not for a backfill of every account (ADR-033). Not measured by a test; the AT-1 hop, sign-up to committed facts, measured 0.6 to 1.7 s (`AccessFeedLatencyIT`, #68).
3. **The relay's row lock is held up to about 16 s** while it calls the log (CLAUDE.md rule 15). No test asserts the bound.
4. **A `set` that commits inside a sign-up's window gives that sign-up 500** (#65). Nothing calls `set` yet. Option (a) from the #65 review, an optimistic-lock failure answered as 409 with `Retry-After`, is recorded on WAR-57 for the Collections slice.
5. **Access may answer from a copy that is behind** while the log is down (ADR-036, chosen for D13). `AccessStale` and `AccessReadingFailing` say so.
6. **The rebuild has downtime** (§4.1).
7. **No deployed environment:** the alert rules are evaluated nowhere yet, and Access's scrape job (`access`) is an assumption written in the rules' header (ADR-030).
8. **The subscriber is the account** (ADR-033), until users arrive with proposed 032.

## 5. Fitness functions added

| Function | Test | Rule |
|---|---|---|
| F7, billing side | `architecture.F7AccessFactsContractTest` | Every message `accessfeed` can build is valid against `schema.json` and carries every field in `consumer-access.json`; in billing's commit stage. Shown failing on a deliberate mistake (#67) |
| F7, Access side | `com.tallyaccess.NoBillingCodeTest` | Access imports no `com.tally` class |
| Consumer fields | `com.tallyaccess.ConsumerAccessFieldsTest` | `consumer-access.json` lists exactly the fields Access's message type reads, both ways (#67 review) |
| Consumer examples | `com.tallyaccess.ExamplesTest` | Access reads every file in `examples/`, the unknown field and values included |
| F12, extended | `architecture.F12AccessReactsOnlyToEventsTest.theAccessFeedUsesOnlyTheEventsOfSubscriptionAndAccount` | `accessfeed` uses only the events of `subscription` and `account`. The old half for the in-process `access` left with it (#72) |
| F14, Access side | `com.tallyaccess.F14AccessMetricsIT` | Every `tally_access_` metric a rule reads is served by Access; at least one is read (#71) |
| F14, billing side, changed | `architecture.F14AlertRulesAgreeIT` | Leaves `tally_access_` metrics to Access's side; a runbook no rule names is allowed only on `NAMED_BY_NO_RULE` (`access-rebuild.md`), and the list must name files that exist |
| F15 | `architecture.F15PayloadScanTest` | The `AccessFacts` schema allows no extra properties, and every property is an ID, an enum, a number or a time; a keyword the scan does not understand fails it (#67 review) |
| Runbook statement | `architecture.AccessRebuildRunbookTest` | `access-rebuild.md` holds the statement AT-19 runs to empty Access's database |
| ADR-028 check | `architecture.AccessListenerDrainedIT` | V35 stops on an incomplete publication of the removed listener, and lets other listeners pass |
| Library behaviour, pinned | `SpikeS2CompactionIT`, `SpikeS3EmptyDatabaseOldGroupIT`, `SpikeS4UnacknowledgedSendIT`, `platform.internal.ListenerGoneIT` | §2 |
| Alert rule tests | `ops/alerts/tally.rules.test.yml` | A firing and a quiet test for `AccessDown`, `AccessFeedBehind`, `AccessStale`, `AccessReadingFailing`, `AccessValueNotKnown` and `EventListenerGone` (23 rules in all) |

Kept and extended: `PinnedEventClassesTest` (`StandingChanged`), `PinnedListenersTest` (the two `accessfeed` listeners in; `AccessService.on` out), `PinnedModuleDependenciesTest` (`accessfeed` in; `access` out), `ModuleStructureTest` (nine modules), `ComponentDiagramTest` (the diagram regenerated in #72: only the Access node and its edge went).

## 6. Audit of the decision records this slice touched

The "Checked by" column of `docs/architecture/v2/README.md`, against the code. A check counts only if a test exists. Rows 033 to 036 are "Proposed", and that table has no "Checked by" column; for them the audit compares what the record's text says with the tests that exist, and suggests the column's entry.

### 6.1 The records the user named

| Record | The record says | Check in the code | Gap |
|---|---|---|---|
| **ADR-028** (pinned listeners; remove a listener only when it has none incomplete). Checked by: `PinnedListenersTest` | Identity pinned; "wait until the old one has none, then remove it" | `PinnedListenersTest` (identities pinned); `AccessListenerDrainedIT` and V35 (the removal of `AccessService.on`); `ListenerGoneIT`, `tally.events.listener_gone`, `EventListenerGone` with promtool tests (the time after) | **The column names only `PinnedListenersTest`.** "Wait until the old one has none" had no check until #72, and V35 checks one listener at one moment; the general check is `EventListenerGone`. Suggested column: `PinnedListenersTest`; for each removal, a migration check like V35 (`AccessListenerDrainedIT`); `EventListenerGone` (`ListenerGoneIT`) |
| **ADR-033** (the access feed) | (1) one row per account, +1 only on change; events only | `AccessFeedIT` (10, including two copies at the same time, out of order, no sequence, values not known); F12; `PinnedModuleDependenciesTest` | None |
| | (2) one send at a time per account; each a transaction, committed on acknowledgement; Access reads committed only | `AccessFeedOrderIT` (two instances, broker paused); `AccessFactsRelayIT` (7); `SpikeS4UnacknowledgedSendIT`; `AccessLogIT` (an aborted message is never applied, shown failing under `read_uncommitted`) | **The gap through two relays has no test of its own** (decided on #68); it is driven in S-4 and through one relay |
| | Known limits: the 15 s hold-back; a few hundred accounts a second; the 16 s lock | — | **No test measures any of the three** (§4.4) |
| | Assumptions: a value not known never takes access away, and is counted; subscriber = account | `AccessRulesTest`; `ExamplesTest` (`future-unknown-values.json`); `AccessLogIT` (counted); `AccessFeedIT.aValueTheFeedDoesNotKnowChangesNothingAndIsCounted`; `AccessContractIT` | None |
| | Suggested column | `AccessFeedIT`; `AccessFeedOrderIT`; `AccessFactsRelayIT`; `SpikeS4UnacknowledgedSendIT`; `AccessLogIT`; AT-1, AT-20, AT-21 | |
| **ADR-034** (the contract) | Billing's commit stage fails on a message that is invalid or lacks a field Access reads; Access's tests read every example | `F7AccessFactsContractTest` (commit stage); `ExamplesTest`; `ConsumerAccessFieldsTest` | None. Suggested column: F7 (`F7AccessFactsContractTest`); `ExamplesTest`; `ConsumerAccessFieldsTest` |
| **ADR-035** (the position in Access's database) | Facts and positions in one transaction; a partition starts at the stored position or the beginning; never a broker commit; an emptied database rebuilds with no operator step | `AccessLogIT.anEmptiedDatabaseIsRebuiltFromTheStartWithTheOldGroup`; AT-19 (old group; compacted and not; one of two instances stopped); `AccessRecoveryIT.aFailedApplyIsReadAgainAndNoMessageIsSkipped` | **"The position only moves forward" has no test of its own**: the `greatest` in `JdbcFactsStore.advance` is exercised by AT-19's two instances but never driven backwards. Suggested column: AT-19; `AccessLogIT` |
| **ADR-036** ("complete once"; liveness) | A marked copy answers at once, also with the log down; an emptied database waits; liveness breaks only when the reading thread ends; the metric shows how long reading has failed | `AccessStartWithoutLogIT` (3: marked copy with the log down, emptied database, mark left behind); `ReadinessTest`; `AccessRecoveryIT` (a store that keeps failing leaves liveness correct and the metric rises; a thread that ends breaks liveness) | **A running instance's view of a log outage has no test** (§4.1, PR-8 note 1). Suggested column: `AccessStartWithoutLogIT`; `AccessRecoveryIT`; rule tests for `AccessReadingFailing` |

### 6.2 The other records the slice touched

| Record | Checked by | Check in the code | Gap |
|---|---|---|---|
| ADR-001 | Pinned graph; Access-survives-billing test (Slice 2) | `PinnedModuleDependenciesTest`; AT-18; `NoBillingCodeTest` | None: the Slice 1R gap is closed |
| ADR-006 | Event payload scan | F15 (`F15PayloadScanTest`) | None for the message to Access |
| ADR-007 | Failover test before launch | Access's own PostgreSQL in tests and in `infra/docker-compose.yml`; AT-18 | **No failover test** (before launch, as the record says) |
| ADR-008 | Pipeline configuration | `ci.yml`, `access.yml` (one pipeline per deployable; billing's acceptance stage builds Access's jar) | **No staging or production stages** (unchanged) |
| ADR-009 | Migration check; N−1 replay | `DestructiveMigrationsTest` (V36 carries its `-- contract:` line); FF-13 | **No N−1 replay of the access-facts message**; the contract is add-only by F7 and F15, not by a replay |
| ADR-011 | Contract tests | Refined by 034; as 034 | None |
| ADR-012 | Rebuild test | AT-19 | None |
| ADR-014 | AT-1 | AT-1 through the Access service | None |
| ADR-016 | FF-12; AT-17 | `StandingChanged` pinned and carrying a sequence; `UnknownEnumValuesIT` (now with the feed's listener) | None |
| ADR-029 | F13; `PerContextDatabase` | Each test class gets its own topic (`EventLog.feedSettings`) and Access its own database (`AccessService`) | None |
| ADR-030 | promtool; F14 | promtool in billing's commit stage; F14 on both sides | None |
