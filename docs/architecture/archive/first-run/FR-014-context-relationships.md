# ADR-014. Context relationships

**Status:** Accepted

## Context
Without explicit relationships, Invoicing and Payment would depend on each other, and Invoicing would have to learn four upstream models. The core (Catalog and Pricing) changes often and must stay free to evolve.

## Decision
- **Catalog and Pricing** offers an open-host service with a published language (`PriceQuote`). Downstream teams consume it as a service. No customer–supplier relationship into the core.
- **Account** is consumed as conformist.
- **Payment knows nothing about invoices.** It accepts an amount and an opaque reference, and reports results by reference. Invoicing maps references to invoices.
- **Invoicing owns an anticorruption layer** that builds its `BillingEvent` timeline from Subscription, Catalog and Pricing, Account and (later) Collections.
- **External systems** (payment provider, tax service, partner bundles) are reached through anticorruption layers.
- **`Money`** is a shared kernel, kept tiny.

Business justification: time to market for the core, and freedom to switch providers.

## Consequences
- No cycles. Payment stays extractable.
- Invoicing owns the reference mapping, and must handle unknown references.

## Compliance
Automated. ArchUnit: nothing in `payment` imports from `invoicing`. Spring Modulith dependency declarations match this record.

## Drivers
D5, D8, D9.

## Revisit when
A second consumer needs the billing timeline: extract it as an open-host service (like Kill Bill's `junction`).

## Notes
Recorded retroactively in the Step 7 review; decided in the context map lesson. Guard against vetoing customers added in the domain discovery backfill.

Cross-reference added after slice 1: extended by ADR-035 (Catalog and Pricing may read Account).

Archived; see `docs/architecture/v2/README.md`.
