-- Test-only queue tables for the platform's own tests. Same shape as QueueDefinition documents.
CREATE SCHEMA platform_test;

CREATE TABLE platform_test.work_queue (
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
CREATE INDEX ON platform_test.work_queue (due_at) WHERE status IN ('READY', 'IN_PROCESSING');

CREATE TABLE platform_test.jobs (LIKE platform_test.work_queue INCLUDING ALL);
CREATE TABLE platform_test.polled_queue (LIKE platform_test.work_queue INCLUDING ALL);
CREATE TABLE platform_test.health_queue (LIKE platform_test.work_queue INCLUDING ALL);
CREATE TABLE platform_test.health_jobs (LIKE platform_test.work_queue INCLUDING ALL);

-- A table without the queue shape, to check registration fails fast.
CREATE TABLE platform_test.not_a_queue (id BIGSERIAL PRIMARY KEY, queue_key TEXT NOT NULL);
