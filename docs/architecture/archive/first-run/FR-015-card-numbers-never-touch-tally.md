# ADR-015. Card numbers never touch Tally

**Status:** Accepted

## Context
Handling card numbers would require an isolated, audited component and a large card-industry compliance scope.

## Decision
Customers enter card details in the provider's hosted form. Tally stores only the provider's token. Tally never receives, stores, sends or logs a card number.

Business justification: cost (much smaller compliance scope) and security.

## Consequences
- No isolated card component is needed.
- Tokens belong to one provider. Moving customers to another provider needs a provider-to-provider card data transfer, on the providers' timeline. This weakens D8.

## Compliance
Manual security review of every checkout change. Log scanning for card-number patterns in staging.

## Drivers
Constraint. Trades against D8.

## Revisit when
A business need requires handling card data directly.

## Notes
Recorded retroactively in the Step 7 review; decided in the Lesson 1 worked example.

Archived; see `docs/architecture/v2/README.md`.
