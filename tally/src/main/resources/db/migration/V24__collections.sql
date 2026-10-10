-- Slice 1R PR-8 (ADR-005; design.md section 5): Collections, its own schema. One open case per account;
-- the day-3 retry is a claimed row with a due time on Collections' own queue.
CREATE SCHEMA collections;

CREATE TABLE collections.collection_case (
    id         UUID        PRIMARY KEY,
    account_id UUID        NOT NULL,
    invoice_id UUID        NOT NULL,
    status     TEXT        NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    opened_at  TIMESTAMPTZ NOT NULL,
    closed_at  TIMESTAMPTZ,
    version    BIGINT      NOT NULL
);
-- The guard of "one case per account" (design.md section 5): a second open case is never inserted.
CREATE UNIQUE INDEX collection_case_one_open_per_account ON collections.collection_case (account_id)
    WHERE status = 'OPEN';

-- Each retry of a case, once: the retry queue's permanent duplicate guard (FR-037).
CREATE TABLE collections.case_retry (
    case_id      UUID        NOT NULL REFERENCES collections.collection_case (id),
    number       INT         NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT case_retry_one_per_case_and_number UNIQUE (case_id, number)
);

CREATE TABLE collections.retry_queue (
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
CREATE INDEX ON collections.retry_queue (due_at) WHERE status IN ('READY', 'IN_PROCESSING');
