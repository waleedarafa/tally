-- Invoicing's record of each subscription and its billing calendar anchor (ADR-002, ADR-014).
CREATE TABLE invoicing.subscription_billing (
    subscription_id UUID  PRIMARY KEY,
    account_id      UUID  NOT NULL,
    plan_id         TEXT  NOT NULL,
    anchor_day      INT   NOT NULL CHECK (anchor_day BETWEEN 1 AND 31),
    started_on      DATE  NOT NULL
);

-- The billing schedule (ADR-017): one item per account and billing date (queue_key
-- '<account>:<date>', the D1 business key), due at local midnight. Shape documented on QueueDefinition.
CREATE TABLE invoicing.billing_schedule (
    id              BIGSERIAL    PRIMARY KEY,
    queue_key       TEXT         NOT NULL UNIQUE,
    due_at          TIMESTAMPTZ  NOT NULL,
    payload         TEXT         NOT NULL,
    status          TEXT         NOT NULL CHECK (status IN ('READY', 'IN_PROCESSING', 'DONE', 'FAILED')),
    attempts        INT          NOT NULL DEFAULT 0,
    claim_token     UUID,
    claimed_at      TIMESTAMPTZ,
    claimed_by      TEXT,
    last_error      TEXT,
    last_success_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ON invoicing.billing_schedule (due_at) WHERE status IN ('READY', 'IN_PROCESSING');
