-- Slice 1R PR-10: the entitlement module becomes access (FR-013 superseded; design.md §1).
-- A one-step rename, which expand and contract (ADR-009) would normally forbid: it is safe only
-- because no production database exists. Tables, rows and constraints move with the schema.
ALTER SCHEMA entitlement RENAME TO access;
