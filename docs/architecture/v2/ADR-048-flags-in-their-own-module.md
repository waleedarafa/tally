# ADR-048. Flags live in their own billing module

**Status:** Accepted, 24 November 2026 (WAR-66; taken by delegation).

## Context

ADR-010 already stands: flags live in a billing table, on per account or country, recorded in the PriceQuote reason, with changes logged, and removed within 30 days of full release. Hosted percentage flags stay rejected (they would break O3).

WAR-66 builds that switch. The Design Record snapshot (`design-record.md`, `design.md` §3) does not list a flags module, and the snapshot is not edited. The flag catalogue is not offer, account, or platform logic: Offers will record each flag's state on a quote, but ops flags and later canaries (F-07, F-09) are not offer concerns, account does not own delivery switches, and platform must not hold business logic.

## Decision

Flags live in a new `flags` module (`com.tally.flags`), with its own PostgreSQL schema when persistence lands. The module depends on `shared` and `platform` only. It does not depend on `account`: evaluation takes an account id and an ISO 3166-1 alpha-2 country code.

A newly defined flag is off by default. An account assignment overrides a country assignment; if neither is set, the default applies. Changes are logged. Full release is explicit, and is allowed only when the default is on and no remaining assignment is off. A flag that has been fully released for more than 30 days and has not been removed is overdue. After release, the only allowed change is to remove the flag.

`offers` will depend on `flags` when PricingService records every defined flag's state in the quote reason (WAR-66 step 4). Until then the approved graph lists `flags` with no downstream dependents.

V55 already tightened `account.country`. The flags tables are a later, additive migration (V56).

## Consequences

- The pinned module list and `architecture/module-dependencies.txt` gain `flags`. `design.md` §3 is left as the snapshot.
- Pricing a period or a mid-period change can read flag state without Offers owning the catalogue.
- A later ops or canary flag does not have to live in Offers.

## Rejected

- Putting the catalogue in `offers`: the switch is broader than pricing, and F-09 is not an offer concern.
- Putting it in `platform`: platform holds no business logic (FR-027).
- Putting it in `shared`: the shared kernel stays Money, Ratio and DomainEvent (F4).
- Putting it in `account`: a country on the account is an input to evaluation, not the home of the switch.
