# ADR-020. One repository; the fake provider is a separate application

**Status:** Accepted

## Context
The payment adapter and the fake provider change together. Timeouts and outages can only be tested if calls cross a real network.

## Decision
One repository with a parent `pom.xml` and these parts: `tally/` (one Maven module, per ADR-003), `fake-provider/`, `load-tests/`, `infra/`, `docs/architecture/`. The fake provider is a separate Spring Boot application reached over HTTP. It supports modes for slow responses, timeouts, outages, rate limiting, and lying about outcomes.

Business justification: time to market. One pull request updates both sides of the payment contract.

## Consequences
Every build sees everything unless builds are scoped.

## Compliance
Manual.

## Drivers
D3, D6, D8.

## Revisit when
A separate team owns a part with its own release cycle.

## Notes
Recorded retroactively in the Step 7 review; decided in Steps 3 and 5.
