# Runbook: Access stale

Access keeps its copy current by reading the event log; its position per partition is in its own database, with the facts (ADR-035). Each instance reads the partitions the broker gives it. A failure of the log or of Access's database never breaks liveness, since a restart repairs neither (ADR-036); these alerts say so instead.

## Who

The on-call engineer for Access.

## When

- **`AccessStale`** (page, D13): the log has held messages for over 2 minutes that an instance has not applied (`tally_access_unapplied_oldest_age_seconds`). Its answers are older than the log: a blocked account may still watch, a new subscriber may not.
- **`AccessReadingFailing`** (page, D13): an instance has failed to read the log, or to write its copy, for over 2 minutes (`tally_access_reading_failing_seconds`). It keeps trying, from its stored position, and answers from its copy meanwhile.

## See what is wrong

```sh
curl -sS http://<instance>:8090/actuator/prometheus | grep -E 'tally_access_(unapplied|reading|last_applied)'
```

In the instance's log: `Reading the log failed; reading again from the stored position in ...` has the exception. `Access reads the log again, after ...` says it recovered.

```sql
-- Access's database: the stored position of each partition.
SELECT topic, partition, next_offset FROM access.log_position ORDER BY partition;
```

Compare with the end of the log:

```sh
kafka-consumer-groups.sh --bootstrap-server <broker> --describe --group <access group>
```

The group commits no position (ADR-035), so its `CURRENT-OFFSET` is empty; `LOG-END-OFFSET` is the end to compare with.

## What to do, by cause

### (a) Access's database is down or full
Both alerts fire, and answers fail too. Restore the database; Access reads on from its stored position, and nothing is skipped.

### (b) The log is down or unreachable from Access
`AccessReadingFailing` fires; `AccessStale` fires too if there were messages it had not applied. Restore the broker, or the network between Access and the broker. Billing's `AccessFeedBehind` usually fires as well.

### (c) An instance is slow, not failing
`AccessStale` fires alone, and `tally_access_unapplied_oldest_age_seconds` keeps rising while the instance reads. Add an instance (the broker moves partitions to it), or look for a slow database.

## Never

- Never reset the consumer group's offsets: Access does not use them (ADR-035).
- Never restart every instance at once to clear these alerts: a restart repairs neither the log nor the database, and while the instances start, no one answers.
