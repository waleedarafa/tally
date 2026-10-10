# ADR-006. Deployment platform and environments

**Status:** Accepted

## Context
Tally runs background workers between web requests. One team must operate it. Options considered: Kubernetes, a managed container service with always-on instances, a virtual machine with Docker Compose.

## Decision
We will run Tally on a managed container service with always-on instances:
- Two Tally instances in two availability zones.
- Managed PostgreSQL with a standby in a second zone.
- One fake provider instance.
- Four environments: local, CI, staging, production.
- One container image per commit, promoted through all environments. Only configuration differs.

Business justification: cost. Low operational load for one team.

## Consequences
- Rolling deployments, health checks and load balancing come built in.
- Some lock-in to one cloud provider.
- Platforms that scale to zero or throttle CPU between requests are excluded, because background workers would silently stop.
- The instance count affects the rate limit in ADR-005.

## Compliance
Infrastructure is defined as code. Only the pipeline changes production. Manual review of platform settings for always-on CPU.

## Drivers
D6, D7, D12.

## Revisit when
The organisation already runs Kubernetes well, or several teams need a shared platform (see ADR-008).

## Notes
Decided in Step 5, Part B.

Archived; see `docs/architecture/v2/README.md`.
