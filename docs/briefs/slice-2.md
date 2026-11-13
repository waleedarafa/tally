# Slice 2 brief: Access as its own deployable

## Goal

Today Access is a module inside the billing process. It shares billing's process and billing's database.

Example: a bad billing release crashes every billing copy at 20:00. A household opens the app. The app asks `GET /v1/accounts/{id}/access`. No process answers. The household paid, and it cannot watch.

D13 says: "Can I watch?" is answered 99.9% of the month, also while billing is down. ADR-001 gives the design: Access is a second deployable (a second quantum), with its own copy of who may watch.

When this slice is done:

- Access runs as its own process, with its own PostgreSQL.
- Billing sends the current access facts of each account to an event log.
- Access reads the log and answers from its own copy.
- A test stops every billing copy and the billing database. Access still answers.
- A test empties Access's database. Access builds its copy again from the log.

Step 0 of this slice is done (test isolation, alerts: ADR-029, ADR-030, ADR-031). This brief covers the remaining steps.

## Read first

1. `docs/architecture/v2/README.md`: decisions 001, 006, 007, 008, 009, 011, 012, 014, 016, 024, 028, 029, 030.
2. `docs/architecture/v2/design-record.md`: §7 "Access", §9 (ADR-001), §10 (F7, "Access's contract"), §13, §14, §16 ("To Access"), and "Inputs to later slices".
3. `docs/architecture/v2/design.md` §3 (the graph and its TEMPORARY line) and §8 (`GET /v1/access/{subscriberId}`).
4. `docs/briefs/slice-1-spikes.md`, questions 2 and 3: what Spring Modulith does when publications are resubmitted.

## What exists today (checked against `main` at d3b73b2)

- `com.tally.access` is 92 lines. `AccessService.on(SubscriptionChanged)` inserts one row in `access.access` when the type is STARTED. Nothing removes a row.
- `GET /v1/accounts/{id}/access` answers `{accountId, hasAccess}`. `openapi.yaml` says this endpoint stays "until Slice 2".
- The pinned graph has `access: shared, platform, subscription, account`, marked TEMPORARY. F12 checks that Access uses only events.
- `AccountStanding.set` changes the standing. It publishes no event. Nothing calls it yet.
- The listener `AccessService.on(SubscriptionChanged)` is in `pinned-listeners.txt` (ADR-028).
- The repository has two Maven modules: `fake-provider`, then `tally`. Tests build the fake provider's image from its jar.

## Decisions to confirm before PR-1

The Design Record decides the shape (ADR-001, 006, 012). It does not decide the four points below. Each has a recommendation. PR-1 records them as proposed, and no code starts before the user confirms them.

### 1. Where billing builds the access facts (proposed ADR-033, part 1)

ADR-012 says the log holds the **current access facts of each account**: `AccessFacts(account, sequence, sources, standing)`. Some code in billing must build that message from two inputs: `SubscriptionChanged` (Subscription) and a standing change (Account).

| Option | What it means | Good | Cost |
|---|---|---|---|
| **A. A new billing module, `accessfeed`** (recommended) | It listens to the two events. It keeps one row of current facts for each account, with a sequence. It sends that row to the log. It holds no access rule. | One place builds the message. The row is also the outbox. Access's rules stay in Access. | One more module. The graph gets `accessfeed: shared, platform, subscription, account` (events only). |
| B. Subscription builds the message | Subscription listens to standing changes and publishes the facts. | No new module. | Subscription learns about standing and about Access's message. Its reasons to change grow. |
| C. Two topics, one for each source module | Each module sends its own events to the log. Access joins them. | No new billing code path. | Not what ADR-012 and §16 decided. The answer's `sequence` has no single meaning. A rebuild must join two topics. |

Build A. The TEMPORARY edge of `access` goes away, as the Design Record says. A new, permanent edge takes its place: `accessfeed` uses the **events** of `subscription` and `account`, never their APIs. This brief is the approval that FR-040 asks for, once the user confirms it.

### 2. How the facts reach the log (proposed ADR-033, part 2)

The log is compacted: for each account, it keeps the last message. So the **last message for an account must be the newest facts**. If sequence 5 lands after sequence 6, a running Access ignores it. But a rebuild reads the log from the start, ends on sequence 5, and gives a wrong answer for that account until the next change.

| Option | Good | Cost |
|---|---|---|
| A. Spring Modulith event externalization (`spring-modulith-events-kafka`) | Very little code. | The reference documentation says externalization runs on several threads, and "the externalization of later events might overtake former ones", in particular when publications are resubmitted. `serialize-externalization` sends one at a time, but in one instance only. Billing runs two or more copies. |
| **B. Tally's own relay on the platform queue** (recommended) | Order is a property Tally controls and tests. It reuses the queue, its health metrics and its alerts (rule 8). | More code to write and to test. |
| C. Change data capture (for example Debezium) | No relay code in Tally. | A new piece of infrastructure for about six people (G9). |

Build B, with this property: **for one account, only one send runs at a time, and each send carries the facts that are current when it starts.** Then a later send can never carry an older sequence. PR-5 must prove the property with two billing instances and a broker failure. If the platform queue cannot give this property, stop and report.

### 3. The contract between billing and Access (proposed ADR-034, refines ADR-011)

| Option | Good | Cost |
|---|---|---|
| **A. A JSON Schema, plus a file that Access owns: the fields it reads, and example messages** (recommended) | No new framework. Both sides test against plain files in `contracts/`. | Tally writes the two small checks itself. |
| B. Pact (message pacts) | A known tool for consumer-driven contracts. | A new dependency and a broker or shared files, for one consumer in the same repository. Compatibility with Spring Boot 4.1 and Java 25 is not checked. |
| C. A shared library of event classes | No translation. | Rejected in Design Record §10: both quanta would deploy together. |

Build A. Billing's commit stage fails when a change breaks a field that Access reads (F7).

### 4. Assumptions (say so in the code and the report)

- **Subscriber = account, in this slice.** The endpoint is `GET /v1/access/{subscriberId}`, and the ID is the account ID. Users arrive with decision 032. With one account for each user (decided 13 November 2026), the mapping stays one to one.
- **A value Access does not know never takes access away.** An unknown standing is not BLOCKED. A source with an unknown state counts as active. Reason: D13 and trade-off T3 ("a cancelled subscriber may watch a few minutes longer; a paying one is never locked out"). Access counts each such value in a metric.
- **No production database exists.** So no backfill of the feed is built. If a shared database exists, stop and ask.

## Target shape

```
subscriber app ──POST /v1/subscriptions──► BILLING (2+ copies, one database)
                                             subscription ─ SubscriptionChanged ─┐
                                             account ────── StandingChanged ─────┤ (events, in process, ADR-002)
                                                                                 ▼
                                                                    accessfeed: one row of facts per account,
                                                                    sequence + 1 only when the facts change
                                                                                 │ relay: one send at a time per account
                                                                                 ▼
                                             EVENT LOG: topic access-facts, key = account ID, compacted
                                                                                 │
                                                                                 ▼
subscriber app ──GET /v1/access/{id}──────► ACCESS (2+ copies, own database)
                                             applies a message only if its sequence is higher
```

## The message: AccessFacts, version 1

Key: the account ID. Value:

```json
{
  "schemaVersion": 1,
  "accountId": "0b6f…",
  "sequence": 7,
  "producedAt": "2026-11-13T06:00:00Z",
  "standing": "GOOD",
  "sources": [
    { "kind": "SUBSCRIPTION", "id": "5c1e…", "state": "ACTIVE" }
  ]
}
```

- `sequence` counts from 1 for each account. It goes up by one only when the facts change. A repeated event, or a `PERIOD_RENEWED`, changes nothing and sends nothing.
- `standing`: GOOD, OVERDUE or BLOCKED. `kind`: SUBSCRIPTION only. `state`: ACTIVE only. Later slices add values (ADR-016: add only).
- IDs, enums, numbers and times only. No free text, so no personal data can enter (ADR-006, D8).
- The source events can arrive twice, or out of order (rule 7). `accessfeed` keeps, for each source, the last source sequence it applied. A missing source sequence means "order not known": apply it (FF-13).

## The answer: `GET /v1/access/{subscriberId}`

```json
{ "subscriberId": "0b6f…", "canWatch": true, "reason": "ACTIVE_SOURCE", "asOf": "2026-11-13T06:00:00Z", "sequence": 7 }
```

| Access's copy says | canWatch | reason |
|---|---|---|
| At least one active source, standing not BLOCKED | true | `ACTIVE_SOURCE` |
| Standing BLOCKED | false | `ACCOUNT_BLOCKED` |
| No active source | false | `NO_ACTIVE_SOURCE` |
| No row for this ID | false | `NOT_KNOWN` (the app shows "activating", ADR-014) |

`asOf` is the `producedAt` of the facts behind the answer. For `NOT_KNOWN`, `asOf` and `sequence` are null.

## In scope

- Step 0b: the nightly load run passes again.
- `StandingChanged` from Account.
- The `accessfeed` module, the relay, and the event log in tests and in `infra/docker-compose.yml`.
- The contract files and F7.
- The Access service: its own Maven module, database, image, pipeline, OpenAPI file and contract test.
- Acceptance tests AT-18 to AT-21. AT-1 asks the Access service.
- Alert rules, rule tests and runbooks for the feed and for Access (ADR-030).
- Removing the in-process `access` module, in its own later pull request (ADR-028).

## Out of scope

- Authentication on any endpoint (decision 032, WAR-49).
- Cancelling, plan changes, trials as a separate source state (the billing lifecycle slice).
- Collections setting OVERDUE or BLOCKED on a schedule (the Collections slice). This slice only carries the standing.
- Partner bundles and their translation layer (Access rule 5).
- Access's own events (granted, suspended, restored). No consumer exists yet.
- Signed grants (Design Record §15, ADR-014 row).
- Deleting an account from the log (a tombstone). The key design allows it. Account deletion does not exist yet.
- A real managed log, a deployed environment, SLOs for D13 (WAR-70, WAR-71, WAR-75).

## Step 0b: the nightly run

The workflow `Nightly load` ran 19 times, from 9 to 27 October. It failed 19 times, always the same way. AT-9 passes. Then AT-10 cannot start: Docker fails to build `tally-fake-provider-test` ("failed to export image: failed to create image: failed to get layer"). So AT-10 has never run with 10,000 renewals. The workflow is disabled since 27 October.

This matters for Slice 2: the Access image will be built for tests the same way.

- Find the cause first. Do not guess. Show the evidence in the pull request.
- Fix it so that the same fix also serves the Access image.
- Run the workflow by hand (`workflow_dispatch`) until it passes two times. Then enable the schedule again, and say so.

## Spikes, before PR-2

Write the results in `docs/briefs/slice-2-spikes.md`, with test names and logs. If a result differs from what a decision record assumes, stop and report.

| Spike | Question | Why |
|---|---|---|
| S-1 | Which Kafka client and which Testcontainers module work with Spring Boot 4.1.1 and Java 25? Take versions from the official documentation and from Spring Boot's dependency management. | CLAUDE.md "Stack": do not guess versions or APIs. |
| S-2 | On a real broker in a container: before compaction runs, does a reader from the start see several messages for one key? After compaction runs, does it see only the last? Which topic settings make compaction run in a test? | ADR-012 assumes that a rebuild grows with the number of accounts, not with history. Kafka's documentation says a reader from the start sees "at least the final state" and that compaction "does not guarantee there is only one record with the same key". So the rebuild must apply by sequence. The spike shows both facts. |
| S-3 | When Access restarts with an empty database and an old consumer group, does it read from the start? What must an operator reset? | The rebuild runbook must name exact steps. |

## Pull requests, in order

Each is a short-lived branch, merged through a pull request (ADR-008). `./mvnw verify` is green on every commit. Show each new behaviour test failing first. Linear issues: WAR-51 to WAR-56 (project "Tally · Slice 2").

### PR-0 · The nightly run (step 0b)

### PR-1 · Docs: adopt this brief (no code)
- `README.md`: add proposed 033 and 034 with the text confirmed by the user.
- `design.md` §3: replace the TEMPORARY line with the target graph. `design.md` §4: add `StandingChanged` and `AccessFacts`. §8: the Access answer above.
- `CLAUDE.md`: the changes listed below.
- Keep `module-dependencies.txt` on the current graph until the code changes it. Say how in the pull request.

### PR-2 · Account publishes `StandingChanged`
- A new event class, pinned (`pinned-events.txt`): event ID, time, account ID, sequence (the account's version), standing. Add only; no cause text in the event.
- Published in the transaction of `AccountStanding.set`, only when the standing changes.
- Tests: the event is published once; setting the same standing publishes nothing.

### PR-3 · The `accessfeed` module, without the log
- Schema `accessfeed`. One row for each account: sequence, standing, sources, produced time, and the last sequence that reached the log.
- Listeners for `SubscriptionChanged` and `StandingChanged`, added to `pinned-listeners.txt` in the same commit.
- Rule 7 holds: two copies of one event at the same time change the row once. Use an atomic insert and a lock or version on the row, never "check, then act".
- `module-dependencies.txt` and `package-info.java` get the new line. A fitness function (extend F12, or a new one) fails if `accessfeed` calls an API of `subscription` or `account`.
- The old `access` module still runs, unchanged.
- Tests: STARTED makes sequence 1; a repeat makes nothing; `PERIOD_RENEWED` makes nothing; a standing change adds one; events out of order end on the right facts; an event with no sequence is applied.

### PR-4 · The contract and F7
- `contracts/access-facts/v1/schema.json`; `consumer-access.json` (the fields Access reads); `examples/` with at least: a normal message, a blocked account, a message with a field and enum values that version 1 does not know.
- F7, billing side (commit stage): every message `accessfeed` can build is valid against the schema and has every field in `consumer-access.json`.
- F15, payload scan (commit stage): the schema allows no extra properties, and every property is an ID, an enum, a number or a time. A free-text field fails the build (ADR-006, I-11).
- Show F7 and F15 failing on a deliberate mistake each, then green.

### PR-5 · The relay and the event log
- The log runs in a container in tests, and in `infra/docker-compose.yml`. The topic name comes from configuration. **Each test class uses its own topic** (ADR-029: no test meets a message it did not create).
- The relay runs on the platform queue (rule 5), reports health (rule 8), and waits for the broker's acknowledgement before it records that a sequence reached the log.
- The property from decision 2, proved by a test: two billing instances, many changes to few accounts, the broker stopped and started in the middle. At the end, for every account, the last message in the topic has the account's current sequence, and no message for an account follows one with a higher sequence.
- Crash after the send and before the record: the same sequence is sent again. That is correct.
- Broker down: sign-up still answers 201. The feed's backlog shows in a metric: the age of the oldest facts not yet in the log.

### PR-6 · The Access service
- A new Maven module `access`, between `fake-provider` and `tally`. Base package `com.tallyaccess`. It has no dependency on the `tally` module and imports no `com.tally` class. F7, Access side, fails the build if it does.
- Plain layers (Design Record §10 amendment). Its own Flyway migrations and schema. It injects `Clock` (rule 4).
- The consumer applies a message with one atomic statement: insert, or update only if the stored sequence is lower. It confirms its position in the log only after the database commit.
- The endpoint and answers above. Its own OpenAPI file, `docs/api/access-openapi.yaml`, written first, with a contract test like `ContractIT`.
- **Readiness.** An instance whose copy is behind the end of the log that it saw at start reports "not ready" and serves no answer. Without this, a rebuild answers `NOT_KNOWN` for paying households.
- Consumer contract test: Access reads every file in `contracts/access-facts/v1/examples/`, the unknown field and values included.
- Health and metrics: `/actuator/health`, `/actuator/prometheus`; the age of the last applied message; the count of values it did not know.
- `access/Dockerfile`, as the other two. One pipeline for Access (ADR-008): its commit stage, and one image `tally-access:<commit>` for each commit to `main`. Say in the pull request how the two pipelines share the reactor, and which changes start which pipeline.

### PR-7 · Acceptance tests
The tests start billing instances, the log, and Access as a container built from its jar.

| Test | Pass when | Decision |
|---|---|---|
| AT-1 (changed) | After `POST /v1/subscriptions`, the **Access service** answers `canWatch: true` within 5 seconds. The old endpoint answers the same during this slice. Print the time. | ADR-014 |
| AT-18 Access survives billing | With 100 accounts known to Access: stop every billing instance and the billing database. Access answers every account correctly, for known and unknown IDs. Start billing; a new sign-up reaches Access. | ADR-001, D13 |
| AT-19 Rebuild | Empty Access's database and follow the runbook from S-3. Access is "not ready" until it has caught up. Then every answer equals the answer before. Run it once on a topic where compaction has run, and once where it has not. | ADR-012 |
| AT-20 Blocked and restored | `AccountStanding.set(BLOCKED)` makes `canWatch: false`, reason `ACCOUNT_BLOCKED`. `GOOD` restores it. The sequence goes up each time. | Access rules 2 and 4 |
| AT-21 Old and repeated messages | Access gets sequence 6, then 5, then 6 again. The answer stays on 6. | ADR-006, ADR-016 |

### PR-8 · Alerts and runbooks (ADR-030)
Add to `ops/alerts/tally.rules.yml`, each with a firing test, a quiet test and a runbook. Correct the names against what the two applications export; F14 decides, and now also scrapes Access.

| Alert | Fires when | For | Severity | Driver | Runbook |
|---|---|---|---|---|---|
| `AccessDown` | Prometheus cannot scrape Access | 2m | page | D13 | `access-down.md` |
| `AccessFeedBehind` | The oldest facts not yet in the log are older than 120 s | 1m | page | ADR-014 | `access-feed-behind.md` |
| `AccessStale` | The log has messages that Access has not applied for more than 120 s | 1m | page | D13 | `access-stale.md` |
| `AccessValueNotKnown` | Access counted a value it does not know in the last day | 0m | ticket | ADR-024 | `access-value-not-known.md` |

Also write `access-rebuild.md` (the steps proved by AT-19). F14 allows a runbook that no rule names only if it is on a short list in the test; say so.

### PR-9 · Remove the in-process `access` module (a separate release)
ADR-028: a listener is removed only when it has no incomplete publication. So this pull request merges only after PR-7 is on `main`.

- A check, before the removal: no incomplete publication names `AccessService.on(SubscriptionChanged)`. If Tally has no metric for a publication whose listener no longer exists, add one and an alert rule for it first, and say so.
- Remove the module, its line in `pinned-listeners.txt`, its line in the graph, F12 or its old half, `AccessIT`, and `GET /v1/accounts/{id}/access` from `openapi.yaml` (announced there since Slice 1R; no client exists).
- Drop the `access` schema of the billing database in a migration with a `-- contract:` line.
- AT-1 asks only the Access service.

### PR-10 · Report and documents
`docs/briefs/slice-2-report.md`, with the six points of CLAUDE.md. Update `design.md`, the glossary, the generated component diagram, and the topic tracker rows B-01, B-08, D-08, D-17, D-18, D2-08, D2-09, E-11, H-09 and I-11, each with its evidence.

## CLAUDE.md changes (in PR-1)

- Rule 1: add `accessfeed` to the module list; `access` leaves it in PR-9. Access is a second deployable in `access/`, base package `com.tallyaccess`; it never depends on billing code (F7).
- Rule 7: add "A message to the log is add-only too. `contracts/access-facts/` holds the schema and what Access reads. Change the contract files in the same commit as the code."
- A new rule 15, **The access feed**: "For one account, one send at a time; each send carries the current facts. Access applies a message only if its sequence is higher. No personal data in a message (F15)."
- "Read first": add `docs/briefs/slice-2.md` and `contracts/access-facts/v1/`.

## Stop and ask, instead of deciding, when

- The platform queue cannot give the relay's property (decision 2).
- A spike shows that the log or the client behaves differently from ADR-006 or ADR-012.
- AT-1's 5 seconds cannot be met through the log. Report the measured times of each hop.
- Any module other than `accessfeed` needs a new dependency.
- Access needs a field that the message does not carry.

## Evidence used for this brief

- Spring Modulith reference, "Event externalization": externalization "might overtake former ones"; `serialize-externalization` sends "only one event … at a time". https://docs.spring.io/spring-modulith/reference/2.0/events.html
- Kafka design, "Log compaction": "Compaction will never reorder messages, just remove some"; a reader from the start "will see at least the final state of all records"; compaction "does not guarantee there is only one record with the same key at any one time". https://docs.confluent.io/kafka/design/log_compaction.html
- Kill Bill: the Design Record's own check (§13) says Kill Bill has no event log, because it has no separate Access quantum; it uses web callbacks. Nothing in Kill Bill is a model for this slice. `killbill-wiki` was not read for this brief.
