-- Slice 3 PR-2: on ENDED, a subscription leaves the account's facts. Its row moves from fact_source to this table,
-- which keeps the source's last sequence, so an older event of that source (a STARTED that arrives late) is ignored
-- and never brings it back (CLAUDE.md rule 7).
--
-- A table of its own, not a new state in fact_source: the previous release reads every fact_source row's state, and a
-- state it does not know would fail its listeners during a rolling deploy (ADR-009). It never reads this table.
CREATE TABLE accessfeed.ended_source (
    account_id      UUID   NOT NULL REFERENCES accessfeed.account_facts (account_id),
    kind            TEXT   NOT NULL CHECK (kind IN ('SUBSCRIPTION')),
    source_id       UUID   NOT NULL,
    -- The source's own sequence in its ENDED event; null when that event had none.
    source_sequence BIGINT,
    PRIMARY KEY (account_id, kind, source_id)
);
