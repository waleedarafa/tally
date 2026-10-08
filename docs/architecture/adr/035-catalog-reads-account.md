# ADR-035. Catalog and Pricing may read Account

**Status:** Accepted. Extends ADR-014.

## Context
`CatalogApi.quote(accountId, planId, period)` needs the account's currency. Slice 3's promotions will also need account facts, such as when the customer joined.

## Decision
`catalog` may depend on `account`, as a conformist consumer of `AccountApi`.

Business justification: time to market. Pricing rules can use customer facts without each caller passing them in.

## Consequences
The core now depends on another context. Account is stable, so the coupling is cheap (Kaiser: coupling to a stable context costs little).

## Compliance
Automated. FF-11 pins the dependency.

## Drivers
D5.

## Revisit when
Slice 3: decide whether callers pass customer attributes into the quote, or Catalog reads them. If segment rules grow, the first option keeps the core freer.

## Notes
Recorded after slice 1 (deviation D3). The dependency was added before approval; ADR-040 now prevents that.
