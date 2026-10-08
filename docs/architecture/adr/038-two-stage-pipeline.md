# ADR-038. The pipeline has two stages

**Status:** Accepted. Extends ADR-007.

## Context
`./mvnw verify` took about 8 minutes. Moving the slow tests to a nightly run would let a broken guard reach production before the nightly run noticed, because every merge to `main` deploys (ADR-007).

## Decision
- **Commit stage** (every push and pull request): unit, fitness and module tests, then the image. Target: under 5 minutes. Measured: 2.5 minutes.
- **Acceptance stage** (after the commit stage): AT-1 to AT-12, the spikes and the chaos tests. On success, the image is tagged `accepted-<commit>`.
- Only images tagged `accepted-<commit>` may be deployed.
- `main` is the trunk. Work happens on short-lived branches merged through pull requests.
- `./mvnw verify` still runs everything locally.

Business justification: time to market. Fast feedback for developers, full protection for production.

## Consequences
A pull request is green before its slow tests have run. The acceptance stage still blocks deployment.

## Compliance
Pipeline configuration under version control. Deployment jobs accept only `accepted-*` tags.

## Drivers
D2, D6, D7. Delivery metrics: lead time.

## Revisit when
The commit stage exceeds 5 minutes, or the acceptance stage exceeds 20.

## Notes
Recorded after implementation in slice 1b (commit 06a1dad). Answers Q6.
