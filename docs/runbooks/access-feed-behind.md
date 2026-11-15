# Runbook: access feed behind

Billing's `accessfeed` module keeps one row of current access facts per account and sends each change to the event log through its relay, one Kafka transaction per send (ADR-033). Access reads the log.

## Who

The billing team's on-call engineer.

## When

**`AccessFeedBehind`** (page, ADR-014): billing has facts that changed over 2 minutes ago and are not yet in the event log (`tally_accessfeed_unsent_oldest_age_seconds`). A new subscriber waits for access, or a blocked account still watches. ADR-014 promises seconds.

Sign-up still answers 201 while the log is down: the change waits in billing's database, and nothing is lost.

## See what is wrong

In Prometheus: `tally_accessfeed_unsent_oldest_age_seconds`, and the relay queue's `tally_queue_oldest_due_age_seconds{queue="access-feed-relay"}` and `tally_queue_failed{queue="access-feed-relay"}`.

In billing's log: `The log did not acknowledge the facts of account ...; trying again in 5s` means the broker does not acknowledge the relay's sends.

```sql
SELECT account_id, sequence, sent_sequence, produced_at
FROM accessfeed.account_facts
WHERE sent_sequence < sequence
ORDER BY produced_at
LIMIT 20;
```

## What to do, by cause

### (a) The event log is down or refuses the sends
Restore the broker. The relay sends again by itself, the current facts of each account, so nothing needs replaying; Access applies a message only if its sequence is higher.

### (b) The relay is not running
Its queue's items are failed or not claimed: follow `queue-items-failed.md` or `billing-stalled.md`. Check that every billing instance has `tally.accessfeed.log.bootstrap-servers` set.

### (c) Billing is down
Follow `tally-down.md`. Access goes on answering from its copy meanwhile (AT-18).

## Never

- Never write to the access-facts topic by hand. Each message must carry the account's current facts and its sequence (CLAUDE.md rule 15).
