# ADR-045. A sign-up's identity is the account and the Idempotency-Key

**Status:** Accepted, 24 November 2026 (WAR-64; the sign-up guard must not depend on one live subscription per account).

## Context

`POST /v1/subscriptions` already requires an `Idempotency-Key` (ADR-019). The sign-up and its stored response commit together. A request that dies is taken over after the request timeout, and a slow request can still be running when the takeover starts.

Today the duplicate that takeover would insert is stopped by `subscription_one_live_subscription_per_account`. That unique index is also what `AlreadySubscribedException` means (WAR-85): only that named guard is "the account already has a subscription". WAR-64 will drop the index so an account can have several live subscriptions. Once it is gone, a same-key takeover would otherwise insert a second row, and there would be no named constraint left for the 409 path.

The one-live rule must not be reused as the idempotency guard: a takeover of the same request is not "already subscribed", and a later, different sign-up of the same account must be allowed once the index is dropped.

## Decision

For an HTTP sign-up, the subscription ID is a function of the account and the `Idempotency-Key` (`UUID.nameUUIDFromBytes` of `"subscription-sign-up:" + accountId + ":" + key`, the same scheme invoices use for adjustments). A retry or slow-request takeover of the same key therefore addresses the same row.

If that row already exists, `subscribe` returns it and does not record `STARTED` or schedule renewal again. It does not throw `AlreadySubscribedException`.

`AlreadySubscribedException` remains only the one-live-per-account rule (`hasLive` or `subscription_one_live_subscription_per_account`). That keeps WAR-85's named-guard behaviour meaningful until the index is dropped, and after that a new named way of detecting a true duplicate can replace it. A programmatic `subscribe(account, plan)` with no key keeps a random ID, as today.

The Design Record snapshot is not edited.

## Consequences

- The primary key itself stops a same-key takeover from inserting a second row, including while the one-live unique index still exists (the conflict is on the id, not on the account).
- Two sign-ups with different keys are different subscriptions. Until the one-live index is dropped they are still refused as already subscribed. After it is dropped, both may be live.
- The HTTP handler must pass the already-validated key into `subscribe`.

## Compliance

- Two `subscribe` calls for the same account and the same sign-up key return the same subscription id, leave one live row, and publish `STARTED` once.
- A second sign-up of the same account with a different key is still already subscribed, for as long as the one-live unique index remains.

## Drivers

ADR-019; WAR-85; WAR-64; D1.

## Revisit when

The one-live unique index is dropped (the rest of WAR-64). The already-subscribed 409 for a different key then has to change, because a second subscription is allowed.

## Notes

Accepted 24 November 2026, decided by delegation (Waleed, 24 November 2026: "Feel free to take decisions and just let me know"). A short row for 045 is in the decision table in [`README.md`](README.md).
