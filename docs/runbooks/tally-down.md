# Runbook: Tally down

## Who

The billing team's on-call engineer (Design Record §11).

## When

**`TallyDown`** (page, G9): Prometheus has not scraped Tally's `/actuator/prometheus` for 2 minutes. Either every instance is down or hung, or Prometheus cannot reach them. Without a scrape, no other alert can fire.

## See what is wrong

```sh
curl -sS -o /dev/null -w '%{http_code}\n' http://<instance>:8080/actuator/health
curl -sS http://<instance>:8080/actuator/prometheus | head
```

- **Health answers `200`, but Prometheus still fails:** the problem is the network or the scrape configuration, not Tally. Check the target's state in Prometheus (`/targets`).
- **Health does not answer:** check the process and its last log lines. A hung JVM still has a process; take a thread dump (`jcmd <pid> Thread.print`) before restarting it.
- **Health answers `503`:** read the components in the body. The database is the usual one.

## What to do, by cause

### (a) The process is gone
Start it again, and look at the end of the previous log for the reason (an out-of-memory error, a failed migration). A failed Flyway migration stops startup: never edit an applied migration; fix forward.

### (b) The process is hung
Take a thread dump, then restart. Attach the dump to the incident ticket.

### (c) The database is unreachable
Every instance fails health. Restore the database first; Tally reconnects by itself.

## Never

- Never mark a migration as applied by hand to get Tally to start.
