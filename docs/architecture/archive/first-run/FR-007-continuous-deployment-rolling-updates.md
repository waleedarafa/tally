# ADR-007. Continuous deployment with rolling updates

**Status:** Accepted

## Context
Fast flow needs small, frequent releases. Tally's background workers claim work from shared queues, so blue/green would not stop two versions running side by side: green's workers start claiming rows as soon as they start. With two instances, a canary means 50% of traffic.

## Decision
We will use continuous deployment:
- Every merge to `main` is built once, deployed to staging, tested, and promoted to production with no manual step.
- Production uses rolling updates.
- Every worker type shuts down gracefully: stop claiming, finish claimed work, release on timeout. The 30 s grace period exceeds the 5 s provider timeout.
- A failed health check or smoke test rolls the deployment back automatically.

Business justification: time to market.

## Consequences
- Several deployments a day are possible.
- Old and new versions run together for a few minutes. ADR-025 makes this safe.
- The pipeline is critical infrastructure. Its speed caps lead time.

## Compliance
Pipeline configuration under version control. The nightly 2-million-renewal load test and weekly chaos tests run in staging. Delivery metrics are recorded from the first deployment.

## Drivers
D6, D7. Course goal: fast flow.

## Revisit when
Regulation or audit requires release approval. Then add one approval step on an already-tested image.

## Notes
Restructured in Phase 0. Release rules for money changes moved to ADR-024. Data compatibility rules moved to ADR-025.

Archived; see `docs/architecture/v2/README.md`.
