# ADR-058. Release sequencing of the contract migrations in #95 and #96

**Status:** Proposed, 28 November 2026 (review of #95 finding 7 and #96 finding 5). Needs Waleed's decision.

## Context

ADR-009 requires expand and contract over two releases, so a rolling deploy works while the previous release still runs. The review found three steps in the stack that break it:

- **V55** (#95) makes `account.country` NOT NULL in the same release that V54 adds it. The previous release (`main`) does not write `country`, so its `POST /v1/accounts` fails with 500 during the deploy.
- **V52** (#95) drops `subscription_one_live_subscription_per_account`, which the previous release's sign-up guard still relies on.
- **V57** (#96) lets invoices be stored as DRAFT. The previous release maps `Invoice.status` with `@Enumerated(STRING)` and has no DRAFT, so loading a DRAFT row throws (`invoicesFor`, `CaseSettlement.settleWithInvoicing`, `forPaymentReference`).

The standing rule is that an applied Flyway migration is never edited. These migrations have only been applied to throwaway test databases (Testcontainers) and local runs; nothing outside this Mac has them. Fixing this properly needs a release boundary inside the stack, which is a decision, not a code fix.

## Options

A. **Move the contract steps to a later release.** Remove V52 and V55 from #95 and add them back, renumbered, in a follow-up PR merged after #95 is deployed. For V57, ship the DRAFT enum value (and a status converter that maps an unknown value to UNKNOWN) one release before anything writes DRAFT, so #96 becomes two releases. This removes migration files that have been applied in test databases, so it needs an exception to "never edit an applied migration".

B. **Compensate in this release.** Add a new migration that drops NOT NULL on `account.country` again (the new code treats a missing country as not known) and re-tighten it next release. V52 and V57 cannot be compensated this way: re-creating the one-live index breaks ADR-046, and the previous release cannot read DRAFT whatever this release does.

C. **Accept a stop-the-world deploy for this release** and record it as an exception to ADR-009: stop every instance of the previous release, migrate, start the new release.

## Recommendation

A for V52 and V55 (nothing has been deployed, so renumbering is safe), and A for V57. If a rolling deploy is not needed for this learning project's next release, C is the least work.

## Decision

Not decided. Until it is, no code in #95 or #96 is changed for these two findings.
