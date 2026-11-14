-- Access's own copy of who may watch (ADR-001, ADR-012), in its own database. One row per account: the facts of the
-- message with the highest sequence it has applied. The standing and the source states are stored as sent: a value
-- this release does not know is kept, and never takes access away (ADR-033).
CREATE TABLE access.facts (
    account_id  UUID        PRIMARY KEY,
    sequence    BIGINT      NOT NULL CHECK (sequence >= 1),
    produced_at TIMESTAMPTZ NOT NULL,
    standing    TEXT        NOT NULL,
    -- [{"kind":..., "id":..., "state":...}], as in the message.
    sources     JSONB       NOT NULL,
    applied_at  TIMESTAMPTZ NOT NULL
);

-- Where Access is in the log, per partition (ADR-035): written in the same transaction as the facts it read, so they
-- never disagree. Only moves forward. An emptied database has no position: Access reads from the start.
CREATE TABLE access.log_position (
    topic       TEXT   NOT NULL,
    partition   INT    NOT NULL,
    next_offset BIGINT NOT NULL CHECK (next_offset >= 0),
    PRIMARY KEY (topic, partition)
);
