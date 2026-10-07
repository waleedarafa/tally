# ADR-008. Team allocation and growth path

**Status:** Accepted

## Context
Today one person and a coding agent own all contexts. That exceeds Team Topologies' guidance (two or three simple domains, or one complex one, per team). The agent reduces extraneous load, not the intrinsic load of the domain.

## Decision
Today: one stream-aligned team owns all contexts.

If the organisation grows:
- **Offers** (stream-aligned): Catalog and Pricing.
- **Subscriber lifecycle** (stream-aligned): Account, Subscription, Entitlement.
- **Billing** (stream-aligned): Invoicing, then Collections once Invoicing is stable.
- **Payments** (stream-aligned): Payment.
- Interaction modes follow the context map: open-host services become X-as-a-Service.
- No platform team until friction shows the need. Shared pipeline and observability code are improved by all teams.
- Stay one deployable unit, with code ownership rules and module-scoped tests, until a team's release rhythm diverges.

Business justification: time to market. Context boundaries double as team boundaries, so growth needs no redesign.

## Consequences
- The shared pipeline is critical until the first extraction.
- One person on call is a bus factor of one. Accepted for the learning project only.

## Compliance
Delivery metrics measured per team once there are several teams. A CODEOWNERS file per module once there are several teams.

## Drivers
Course goal: fast flow. D5.

## Revisit when
- A team's lead time rises because it waits on others.
- Visible platform friction: duplicated glue, teams waiting on pipeline fixes.
- Tally carries real customers or real money (on-call must then be more than one person).

## Notes
Decided in Step 5, Part B. Business risk of single on-call must be accepted by a business owner before real use.
