-- Slice 2 PR-2 (ADR-033, design.md section 4): the account's version, an optimistic lock and the sequence that
-- StandingChanged carries. Additive: the previous release does not write it, and existing rows start at 0.
ALTER TABLE account.account ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
