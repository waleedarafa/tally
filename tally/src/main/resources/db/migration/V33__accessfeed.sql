-- Slice 2 PR-3 (ADR-033, design.md section 4): the access feed, its own schema. One row of current access facts per
-- account; its sequence goes up by one only when the facts change. For each source, the last source sequence
-- applied, so a repeated or older event changes nothing (CLAUDE.md rule 7).
CREATE SCHEMA accessfeed;

CREATE TABLE accessfeed.account_facts (
    account_id        UUID        PRIMARY KEY,
    -- 0 until the first change of facts; the message's sequence counts from 1.
    sequence          BIGINT      NOT NULL DEFAULT 0,
    standing          TEXT        NOT NULL DEFAULT 'GOOD' CHECK (standing IN ('GOOD', 'OVERDUE', 'BLOCKED')),
    -- The account's version in the last StandingChanged applied; null when none, or none with a sequence.
    standing_sequence BIGINT,
    -- When the current facts were produced: the message's producedAt.
    produced_at       TIMESTAMPTZ,
    -- The last sequence that reached the event log (Slice 2 PR-5's relay).
    sent_sequence     BIGINT      NOT NULL DEFAULT 0
);

CREATE TABLE accessfeed.fact_source (
    account_id      UUID   NOT NULL REFERENCES accessfeed.account_facts (account_id),
    kind            TEXT   NOT NULL CHECK (kind IN ('SUBSCRIPTION')),
    source_id       UUID   NOT NULL,
    state           TEXT   NOT NULL CHECK (state IN ('ACTIVE')),
    -- The source's own sequence in the last event applied; null when it had none.
    source_sequence BIGINT,
    PRIMARY KEY (account_id, kind, source_id)
);
