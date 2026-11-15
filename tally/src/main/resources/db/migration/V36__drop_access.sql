-- Slice 2 PR-9: the in-process access module is gone; Access is its own deployable with its own database (ADR-001).
-- V35 has checked that the module's listener has no incomplete publication (ADR-028).
-- In a rolling deploy this drop would break the previous release while it runs: its listener would fail to write
-- here and leave publications that no later release runs (ListenerGoneIT). Once a production database exists, the
-- drop belongs one release after the release that removes the module (ADR-009).
-- contract: no production database exists yet (as for the schema renames of Slice 1R), so no previous release runs against this one
DROP SCHEMA access CASCADE;
