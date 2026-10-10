# ADR-012. Build billing in-house; offers and pricing are the core

**Status:** Accepted (course assumption)

## Context
Billing is mostly product or utility in the market: hosted billing services exist, and Kill Bill is open source. Building it ourselves is an efficiency gap. It is justified only if part of billing differentiates the business.

## Decision
We will build Tally, assuming that fast pricing experiments are a competitive advantage. Catalog and Pricing is the core domain and gets the most investment. Supporting contexts are built simply. Generic capabilities (card processing, tax, identity, email) are bought.

Business justification: strategic positioning (offer experiments faster than competitors). For this course, also learning.

## Consequences
- We carry the maintenance cost of a billing system.
- Investment is concentrated in Catalog and Pricing.

## Compliance
Manual review at each slice evaluation: is the core still where most change happens?

## Drivers
Business goals 1 and 2.

## Revisit when
- Billing products support our kind of offer experiments. Then re-examine build versus buy.
- Offer experiments stop producing measurable subscriber growth.

## Notes
Recorded retroactively in the Step 7 review; the decision was made in domain discovery. Revisit trigger added in the domain discovery backfill (efficiency gap, climatic pattern "everything evolves").

Archived; see `docs/architecture/v2/README.md`.
