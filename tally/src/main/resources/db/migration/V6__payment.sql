CREATE TABLE payment.payment_attempt (
    id             UUID         PRIMARY KEY,
    reference      TEXT         NOT NULL,
    account_id     UUID         NOT NULL,
    amount_minor   BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency       TEXT         NOT NULL,
    payment_token  TEXT         NOT NULL,
    state          TEXT         NOT NULL CHECK (state IN ('PENDING', 'IN_FLIGHT', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    failure_reason TEXT         CHECK (failure_reason IN ('SOFT', 'HARD', 'UNKNOWN')),
    charge_id      TEXT,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL
);

-- At most one open attempt per reference (brief section 1). UNKNOWN counts as open: it is
-- unresolved until reconciliation says otherwise.
CREATE UNIQUE INDEX payment_attempt_one_open_per_reference
    ON payment.payment_attempt (reference) WHERE state IN ('PENDING', 'IN_FLIGHT', 'UNKNOWN');
CREATE INDEX payment_attempt_by_reference ON payment.payment_attempt (reference, created_at);

-- Payment's durable queue of attempts to make (ADR-017). Shape documented on QueueDefinition.
CREATE TABLE payment.collection_queue (
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
CREATE INDEX ON payment.collection_queue (due_at) WHERE status IN ('READY', 'IN_PROCESSING');
