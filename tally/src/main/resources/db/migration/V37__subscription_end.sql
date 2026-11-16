-- Slice 3 PR-2 (design.md section 5): a subscription can end, and an account has one live subscription, not one
-- subscription. An account whose subscription ended may sign up again.
--
-- Expand and contract (ADR-009). The previous release still runs against this schema: it never writes the new
-- columns, and the defaults make each subscription it starts ACTIVE. It does not know ENDED, so it would renew an
-- ended subscription; nothing ends one in this release (Collections calls the end from Slice 3 PR-4 on), so by the
-- time a subscription can end, every release running knows ENDED.
ALTER TABLE subscription.subscription
    ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ENDED'));

-- Set with the status when it ends; null while it is live.
ALTER TABLE subscription.subscription ADD COLUMN end_reason TEXT CHECK (end_reason IN ('WRITE_OFF', 'FIRST_PAYMENT_DECLINED'));

ALTER TABLE subscription.subscription ADD COLUMN ended_at TIMESTAMPTZ;

-- The sign-up's duplicate guard (CLAUDE.md rule 11): one live subscription per account. Built before the old
-- constraint goes, so an account never has neither.
CREATE UNIQUE INDEX subscription_one_live_per_account ON subscription.subscription (account_id)
    WHERE status = 'ACTIVE';

ALTER TABLE subscription.subscription DROP CONSTRAINT subscription_one_per_account;
