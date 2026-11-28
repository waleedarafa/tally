-- ADR-046 (WAR-64): an account may have several live subscriptions. The application no longer
-- treats subscription_one_live_subscription_per_account as already-subscribed (ADR-045 already
-- identifies a sign-up by account and Idempotency-Key). Expand and contract (ADR-009): the guard
-- is dropped only after the code has stopped relying on it. Same-key retries remain unique on
-- the subscription primary key.
DROP INDEX subscription.subscription_one_live_subscription_per_account;
