# Runbook: Access down

Access is the second deployable (ADR-001): it answers "can I watch?" from its own copy of the access facts, with its own database, and reads billing's event log to keep the copy current.

## Who

The on-call engineer for Access.

## When

**`AccessDown`** (page, D13): Prometheus has not scraped Access's `/actuator/prometheus` for 2 minutes. Either every instance is down or hung, or Prometheus cannot reach them. Households may get no answer; without a scrape, `AccessStale` and `AccessReadingFailing` cannot fire either.

## See what is wrong

```sh
curl -sS -o /dev/null -w '%{http_code}\n' http://<instance>:8090/actuator/health/liveness
curl -sS -o /dev/null -w '%{http_code}\n' http://<instance>:8090/actuator/health/readiness
curl -sS http://<instance>:8090/actuator/prometheus | grep '^tally_access'
```

- **Liveness `200`, but Prometheus still fails:** the network or the scrape configuration (job `access`), not Access. Check the target in Prometheus (`/targets`).
- **Liveness does not answer:** check the process and its last log lines. A hung JVM still has a process; take a thread dump (`jcmd <pid> Thread.print`) before restarting it.
- **Liveness `503`:** the reading thread has ended (log line `Access no longer reads the log: liveness is broken`). The platform restarts the instance; that is the one failure a restart repairs (ADR-036).
- **Readiness `503`:** the instance is up and catching up on the log; it refuses answers until its copy is complete (brief PR-6, ADR-036). That is not this alert: see `access-stale.md`.

## What to do, by cause

### (a) The process is gone
Start it again, and read the end of the previous log for the reason. A failed Flyway migration stops startup: never edit an applied migration; fix forward.

### (b) The process is hung
Take a thread dump, then restart. Attach the dump to the incident ticket.

### (c) Access's database is unreachable
Instances start, but answers fail, and the copy is not written (`AccessReadingFailing`). Restore the database first; Access reconnects by itself and reads on from its stored position.

## Never

- Never empty Access's database to "fix" it: that is a rebuild (`access-rebuild.md`), and every instance refuses answers until it has caught up.
- Never point Access at billing's database. Access depends on no billing code and no billing table (F7).
