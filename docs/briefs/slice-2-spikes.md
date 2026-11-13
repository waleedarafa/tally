# Slice 2 spikes: findings

Three spikes from `slice-2.md`, before PR-2. All three pass. No result differs from what ADR-006 or ADR-012 assumes, so nothing here stops the slice. Each finding names the test that pins it: a broker or client upgrade that changes a finding fails the build.

| Spike | Question | Answer | Test |
|---|---|---|---|
| S-1 | Which Kafka client and which Testcontainers module work with Spring Boot 4.1.1 and Java 25? | `org.apache.kafka:kafka-clients` **4.2.1** and `org.testcontainers:testcontainers-kafka` **2.0.5**, both versions from Spring Boot 4.1.1's dependency management. The broker image is `apache/kafka:4.2.1`. Runs on Java 25. | `spikes.SpikeS2CompactionIT.s1…` |
| S-2 | Before compaction, does a reader from the start see several messages for one key? After it, only the last? Which topic settings make compaction run in a test? | **Yes, and not always.** Before: every message. After: only the last of each key, but only for closed segments; a change after compaction is a second message for the key again. Five topic settings, and the cleaner's back-off, make it run within about 2 seconds. | `spikes.SpikeS2CompactionIT` (3 tests) |
| S-3 | When Access restarts with an empty database and its old consumer group, does it read from the start? What must an operator reset? | **No.** The group resumes at its committed position; `auto.offset.reset` does not apply. The operator stops every Access instance, then resets the group to the earliest offset. The tool exits 0 even when it refuses. A new group reads from the start with no reset. | `spikes.SpikeS3EmptyDatabaseOldGroupIT` (2 tests) |

## S-1. Versions

### Where each version comes from

| Artifact | Version | Source |
|---|---|---|
| `org.apache.kafka:kafka-clients` | 4.2.1 | `spring-boot-dependencies-4.1.1.pom`, property `kafka.version` |
| `org.springframework.kafka:spring-kafka` | 4.1.1 | the same pom, `spring-kafka.version` (not used yet; PR-5 decides) |
| `org.springframework.boot:spring-boot-starter-kafka`, `-kafka-test` | 4.1.1 | the same pom, managed |
| `org.testcontainers:testcontainers-kafka` | 2.0.5 | `testcontainers-bom-2.0.5.pom`, imported by the same pom (`testcontainers.version` 2.0.5, already used for PostgreSQL) |
| Broker image | `apache/kafka:4.2.1` | Kafka's own image, at the client's version |

The module's jar holds two container classes for current images: `org.testcontainers.kafka.KafkaContainer` (Apache's images) and `org.testcontainers.kafka.ConfluentKafkaContainer` (Confluent's). The older `org.testcontainers.containers.KafkaContainer` is also there. The spike uses the first, in KRaft mode (no ZooKeeper).

`tally/pom.xml` adds `kafka-clients` and `testcontainers-kafka` with no version, in test scope. PR-5 moves the client to compile scope when the relay needs it.

**Not checked, and why:** the reference pages of Spring Boot (`docs.spring.io`), Testcontainers (`java.testcontainers.org`) and Kafka (`kafka.apache.org`) cannot be reached from the agent's network; the session refuses those hosts. The versions above come from the published BOMs and the jars, not from the reference pages. If you want the pages checked too, allow the three hosts, and I will add the quotes here.

### Evidence

```
SPIKE-S1 client kafka-clients 4.2.1, broker image apache/kafka:4.2.1, broker reports 4.2.1 (Commit:…)
```

The test asserts both: `AppInfoParser.getVersion()` is 4.2.1, and `kafka-topics.sh --version` inside the broker starts with 4.2.1.

## S-2. Compaction

### Set-up

One broker (`support.EventLog`), shared by the test run; each test creates its own topic with a random name (ADR-029). One partition. The producer uses `acks=all` and idempotence, and waits for each acknowledgement.

The broker's cleaner checks for work every second (`KAFKA_LOG_CLEANER_BACKOFF_MS=1000`; Kafka's default is 15 seconds).

### Findings

| # | Experiment | Observed (log prefix `SPIKE-S2`) |
|---|---|---|
| 1 | Five messages for `account-a`, three for `account-b`; read from the start before compaction | Every message: `account-a=[a1, a2, a3, a4, a5], account-b=[b1, b2, b3]`. **A reader from the start sees history**, not one message per key. |
| 2 | Wait 300 ms, send one message with another key (it rolls the segment), wait for the cleaner | About 2.1 s after the roll: `[4:account-a=a5, 7:account-b=b3, 8:roll=roll]`. **Only the last message of each key is left**, at its original offset. Offsets keep their order: compaction removes and never reorders. |
| 3 | One more change for `account-a` after compaction | `account-a=[a5, a6]`. The new message is in the active segment, which the cleaner never touches. **Two messages for one key again.** |
| 4 | The same, with only `cleanup.policy=compact` (every other setting at its default) | Five seconds after the roll, all five messages are still there. With the defaults, nothing is compacted while a test runs. |

### The settings that make compaction run in a test

| Topic setting | Value | Why |
|---|---|---|
| `cleanup.policy` | `compact` | Compaction instead of deletion by age. |
| `segment.ms` | `100` | The cleaner only cleans closed segments. The active segment closes when a message arrives more than 100 ms after it opened. |
| `min.cleanable.dirty.ratio` | `0.01` | Clean as soon as any part of the log is dirty (default 0.5). |
| `min.compaction.lag.ms` | `0` | A message may be compacted at once. |
| `delete.retention.ms` | `100` | Not needed for this result; it matters only for deletes (tombstones), which this slice does not have. |

The broker setting `log.cleaner.backoff.ms=1000` makes the cleaner look every second.

So a test must send one more message after the ones it wants compacted, more than `segment.ms` later. Otherwise they stay in the active segment, and are never compacted.

### What this means for the slice

- **ADR-012's assumption holds in size, not in count.** A rebuild reads about one message per account plus the changes since the last cleaning, not the whole history. It does not read exactly one per account, at any moment.
- **Access must apply by sequence**, as the brief says: insert, or update only if the stored sequence is lower. "The last message read wins" would be correct here, because compaction keeps order. But only the sequence rule stays correct if a message is ever sent twice or out of order (decision 2's crash case).
- **AT-19 needs the settings above** for its run "on a topic where compaction has run", and the extra message after the roll.

## S-3. Empty database, old consumer group

### Set-up

The same broker. Access is modelled by a consumer in group `access-<random>`, with `auto.offset.reset=earliest` and no automatic commit. It commits its position after it has read (as Access will, after its database commit).

### Findings

| # | Experiment | Observed (log prefix `SPIKE-S3`) |
|---|---|---|
| 1 | Three messages. The group reads them and commits. A new consumer in the **same** group starts, as Access with an emptied database would | `same group, empty database: 0 messages in 10 s`. **It does not read from the start.** `auto.offset.reset` applies only to a group with no committed position. |
| 2 | Reset the group's position while that consumer is running | `Error: Assignments can only be reset if the group '…' is inactive, but the current state is Stable.` **The tool's exit code is 0 anyway.** |
| 3 | Stop the consumer; reset again | Exit 0, and the new position: `access-… spike-s3-… 0 0` (partition 0, new offset 0). |
| 4 | Start a consumer in the group again | `after the reset: 3 messages, from offset 0`. All three, in order. |
| 5 | A consumer in a **new** group | `new group: 3 messages, from offset 0`, with no reset. |

### The steps for the rebuild runbook (PR-8, `access-rebuild.md`; AT-19 proves them)

1. Stop **every** Access instance. A running member makes the reset fail.
2. Empty Access's database.
3. Reset the group to the earliest offset, inside the broker or anywhere `kafka-consumer-groups.sh` reaches it:
   ```
   kafka-consumer-groups.sh --bootstrap-server <broker> --group <access group> \
     --topic <access-facts topic> --reset-offsets --to-earliest --execute
   ```
4. **Read the output, not the exit code.** Success prints a table with `NEW-OFFSET 0` for each partition. A refusal prints `Error: Assignments can only be reset if the group … is inactive` and still exits 0.
5. Start Access. It reports "not ready" until it reaches the end of the log it saw at start (brief, PR-6).

**An alternative to step 3:** start Access with a new group name. The new group reads from the start with no reset. The old group stays behind in the broker until its offsets expire, and every Access instance must get the same new name.

**An option for PR-6, not decided here:** Access could keep its position in its own database, in the same transaction as the facts, and assign the partitions itself instead of joining a group. Then an emptied database means "read from the start" with no operator step. It changes how the brief's "confirms its position in the log only after the database commit" is done, so it is raised as a question, not built.

### Evidence

From the runs that went green (group and topic IDs shortened):

```
SPIKE-S3 same group, empty database: 0 messages in 10 s
SPIKE-S3 reset with a running member: exit 0, Error: Assignments can only be reset if the group 'access-d1d5…' is inactive, but the current state is Stable.
SPIKE-S3 reset with no member: exit 0
GROUP              TOPIC              PARTITION  NEW-OFFSET
access-d1d5…       spike-s3-070f…     0          0
SPIKE-S3 after the reset: 3 messages, from offset 0
SPIKE-S3 new group: 3 messages, from offset 0
SPIKE-S2 default settings, 5 s after the last send: {account-a=[a1, a2, a3, a4, a5], roll=[roll]}
SPIKE-S2 before compaction, from the start: {account-a=[a1, a2, a3, a4, a5], account-b=[b1, b2, b3]}
SPIKE-S2 after compaction (2089 ms after the roll), from the start: [4:account-a=a5, 7:account-b=b3, 8:roll=roll]
SPIKE-S2 one more change after compaction, from the start: {account-a=[a5, a6], account-b=[b3], roll=[roll]}
```

## Questions for the user

1. **The three documentation hosts.** S-1's versions come from the BOMs and jars. If the reference pages must be quoted too, the session needs `docs.spring.io`, `java.testcontainers.org` and `kafka.apache.org`.
2. **Where Access keeps its position (PR-6).** In the broker, as the brief says, which makes the rebuild need the operator's reset (S-3, steps 1 to 4)? Or in Access's own database, with the facts, which makes an emptied database read from the start by itself? The brief's readiness rule works with either.
