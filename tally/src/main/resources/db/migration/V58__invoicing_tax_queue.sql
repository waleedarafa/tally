-- WAR-67 (ADR-050): the money queue invoicing uses when the tax service is unavailable. Its rows are
-- never deleted by the retention job while a quote is outstanding, so the queue is named for its
-- duplicate guard (invoicing.invoice_one_period_per_subscription_and_period). Shape documented on QueueDefinition.
CREATE TABLE invoicing.tax_quote_queue (
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
    created_at      TIMESTAMPTZ  NOT NULL,
    finished_at     TIMESTAMPTZ
);
CREATE INDEX ON invoicing.tax_quote_queue (due_at) WHERE status IN ('READY', 'IN_PROCESSING');
CREATE INDEX ON invoicing.tax_quote_queue (finished_at);
