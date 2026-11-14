-- Slice 2 PR-5 (ADR-033, design.md section 4): the relay's queue. An item is a change of an account's facts; the
-- relay sends the account's current facts to the event log. The platform's queue shape (QueueDefinition). The
-- permanent guard is the one row of facts per account (account_facts_pkey) and its sent_sequence: a send only
-- happens while the facts' sequence is above the last one sent.
CREATE TABLE accessfeed.relay_queue (
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
    finished_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ON accessfeed.relay_queue (due_at) WHERE status IN ('READY', 'IN_PROCESSING');
CREATE INDEX ON accessfeed.relay_queue (finished_at);
