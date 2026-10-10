# ADR-013. Tally's bounded contexts

**Status:** Accepted

## Context
The word "price" means four different things in Catalog, Pricing, Invoicing and Payment. Forcing one model on all of them would make every change a negotiation.

## Decision
Tally has these bounded contexts: Catalog and Pricing, Account, Subscription, Entitlement, Invoicing, Payment, Collections. Tax, identity and email are external.

Three non-obvious boundary calls:
- **Catalog and Pricing are one context.** They share one language and one owner. Kill Bill made the same choice.
- **Subscription and Entitlement are separate.** "Is the customer subscribed?" and "Can the customer use the service?" have different answers when payment fails, during fraud blocks, and for partner bundles.
- **Payment and Collections are separate.** Different language (attempts versus overdue cases) and a different owner (finance).

Business justification: time to market. Each kind of change lands in one context.

## Consequences
Seven modules to maintain. Collections is built in slice 5.

## Compliance
Module tests per context. Lightweight evaluation per slice checks that change scenarios still touch one context each.

## Drivers
D5, D8, D9.

## Revisit when
- Catalog and Pricing's rates of change pull apart.
- EventStorming with pivotal events contradicts a boundary.

## Notes
Recorded retroactively in the Step 7 review; decided in domain discovery.

Cross-reference added after slice 1: extended by ADR-034 (checkout is a process module, not a bounded context).

Archived; see `docs/architecture/v2/README.md`.
