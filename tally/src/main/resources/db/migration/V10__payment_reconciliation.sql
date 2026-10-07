-- Reconciliation (ADR-018): when each UNKNOWN or recently FAILED attempt is next checked
-- against the provider, and how many checks found nothing new (for the backoff).
ALTER TABLE payment.payment_attempt ADD COLUMN reconcile_at TIMESTAMPTZ;
ALTER TABLE payment.payment_attempt ADD COLUMN reconcile_checks INT NOT NULL DEFAULT 0;
CREATE INDEX payment_attempt_to_reconcile ON payment.payment_attempt (reconcile_at)
    WHERE reconcile_at IS NOT NULL;

-- Payment's periodic jobs (ADR-017, ADR-027). Shape documented on QueueDefinition.
CREATE TABLE payment.jobs (
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
