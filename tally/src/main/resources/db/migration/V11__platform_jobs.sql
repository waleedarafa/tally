-- The platform module's own periodic jobs, such as event resubmission (FR-022, FR-027).
-- Infrastructure only: job schedules, no business data. Shape documented on QueueDefinition.
CREATE SCHEMA platform;

CREATE TABLE platform.jobs (
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
