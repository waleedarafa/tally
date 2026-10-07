CREATE TABLE subscription.subscription (
    id         UUID         PRIMARY KEY,
    account_id UUID         NOT NULL,
    plan_id    TEXT         NOT NULL,
    started_at TIMESTAMPTZ  NOT NULL,
    version    INT          NOT NULL,
    -- One subscription per account in slice 1.
    CONSTRAINT subscription_one_per_account UNIQUE (account_id)
);

CREATE TABLE subscription.subscription_change (
    id              UUID         PRIMARY KEY,
    subscription_id UUID         NOT NULL REFERENCES subscription.subscription (id),
    version         INT          NOT NULL,
    type            TEXT         NOT NULL,
    plan_id         TEXT         NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT subscription_change_version UNIQUE (subscription_id, version)
);
