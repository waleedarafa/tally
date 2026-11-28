# ADR-046. An account may have several live subscriptions

**Status:** Accepted, 24 November 2026 (WAR-64; taken by delegation).

## Context

ADR-043 puts one billing day on the account so a later subscription can share it. ADR-045 makes a sign-up's identity the account and the Idempotency-Key, so a retry or takeover of the same key returns the existing row and does not need `subscription_one_live_subscription_per_account` as a duplicate guard. That unique index, and `AlreadySubscribedException`, are what still stop a second live subscription of the same account (WAR-85). WAR-64 is to allow several.

The daily duplicate-invoices check (D1, FR-024) also treats two period invoices with overlapping dates as a violation when they share an account. Two live subscriptions of one account will often share the account billing day, so their period invoices can overlap; that must not be a violation. One subscription still cannot have two overlapping period invoices.

## Decision

An account may have several live subscriptions. `subscription_one_live_subscription_per_account` is dropped (V52). A different Idempotency-Key starts a new subscription; a retry or takeover of the same key still returns the existing row and is not already-subscribed (ADR-045, not edited).

`AlreadySubscribedException` and HTTP 409 `/problems/already-subscribed` are no longer produced by sign-up. A programmatic `subscribe(account, plan)` without a key also starts a new subscription.

Two period invoices with overlapping dates are a violation only when they belong to the same subscription. Two period invoices of different subscriptions of the same account may overlap. The unique period-invoice rule remains per subscription and period (I2).

The Design Record snapshot is not edited. ADR-045 is not edited; this record is the revisit it called for.

## Consequences

- Sign-up no longer consults `hasLive` and no longer maps the dropped index to already-subscribed.
- Concurrent different-key sign-ups both succeed. They serialise on the account row when they write the payment token or the billing day, so one does not fail as `/problems/concurrent-change`.
- A standing `set` that arrives during a sign-up waits for the same account row lock, then applies after the sign-up commits. That race no longer answers 409 `/problems/concurrent-change` (WAR-57's window closed once sign-up locked the row).
- The household still has one billing day (ADR-043). The first subscription to be inserted sets it; later starts use it, and may have a short first period.
