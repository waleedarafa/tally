# Runbook: access value not known

Billing and Access share one contract, `contracts/access-facts/v1/` (ADR-034). Values are added, never renamed (ADR-016). A reader that meets a value it does not know never fails on it.

## Who

The billing team, in working hours.

## When

**`AccessValueNotKnown`** (ticket, ADR-024): in the last day, a reader met a value it does not know.
- `tally_access_values_not_known_total{field}`: Access read a message with a schema version, standing, source kind or source state it does not know. It never takes access away for it: an unknown standing is not BLOCKED, an unknown state still counts as a source.
- `tally_accessfeed_values_not_known_total{field}`: billing's feed read a `SubscriptionChanged` with a change type, or a `StandingChanged` with a standing, that this release does not know. It changes nothing for it.

Either way the two sides run different versions of the contract: usually a rolling deploy in progress, or one side deployed without the other.

## See what is wrong

The alert's `field` label names the field. Check which version of billing and of Access each instance runs.

## What to do

- **A deploy is in progress:** the alert clears once both sides run the same version. Nothing to do.
- **One side is behind:** deploy the side that does not know the value. Add the value to `consumer-access.json` and the examples in the same commit as the code (CLAUDE.md rule 7).
- **Access sees a value no billing version writes:** something other than billing's relay writes to the topic. Find it.

## Never

- Never map an unknown value to a known one by hand in the database.
