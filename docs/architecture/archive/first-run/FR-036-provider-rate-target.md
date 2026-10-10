# ADR-036. Payment calls aim at 95% of the provider's limit

**Status:** Accepted. Extends ADR-005.

## Context
At exactly the provider's limit, two instances still put 101 or 102 calls into some provider seconds. AT-10 measured 0.69% of calls refused with 429 at 10,000 renewals. A provider may block a client that exceeds its limit for long periods.

## Decision
The rate limiter aims at 95% of the provider's limit (`rate-limit-target-percent`, default 95). The CI alarm threshold for AT-10 is 1% of calls refused.

Business justification: cost and risk. A small loss of speed avoids being blocked by the provider.

## Consequences
At 95 calls a second, 500,000 first attempts take about 88 minutes, inside D6's 3 hours. AT-10 at 2,000 renewals: 0.25% refused.

## Compliance
Automated. AT-10 in CI and nightly.

## Drivers
D6.

## Revisit when
More than two instances, or sustained 429s: the deferred shared rate limiter.

## Notes
Recorded after implementation in slice 1b (commit dffcd89). Answers Q1 and R6.

Archived; see `docs/architecture/v2/README.md`.
