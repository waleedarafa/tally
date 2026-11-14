# Runbook: rebuild Access's copy

No alert names this runbook. It is the planned procedure for rebuilding Access's copy from the event log (ADR-012, ADR-035, ADR-036), for example after Access's database is lost or its data is in doubt. AT-19 proves these steps, with the old consumer group and with compaction run and not run. F14 allows it on its short list of runbooks that no rule names.

## Who

The on-call engineer for Access, in a planned window. While the copy rebuilds, the instances refuse answers (503).

## Steps

1. **Stop every Access instance.**
2. **Empty Access's database, in one statement**, so that no part of it is left behind:

   ```sql
   TRUNCATE access.facts, access.log_position, access.copy_state;
   ```

   All three, at once. A "complete once" mark left in `access.copy_state` would make an instance answer at once from an empty copy. Access ignores a mark with no stored position (the mark guard), but the statement must still empty all three.
3. **Start Access with its usual settings**, the same consumer group included. No reset of the group, and no other step in the broker: Access keeps its position in its own database, so an emptied database reads the log from the start (ADR-035; spike S-3).
4. **Wait for ready.** Each instance answers 503, and `/actuator/health/readiness` says 503, until its copy has reached the end of the log it saw at start. Then it answers as before.

   ```sh
   curl -sS -o /dev/null -w '%{http_code}\n' http://<instance>:8090/actuator/health/readiness
   ```

## Never

- Never empty only some of the three tables.
- Never reset the consumer group's offsets; Access does not use them.
