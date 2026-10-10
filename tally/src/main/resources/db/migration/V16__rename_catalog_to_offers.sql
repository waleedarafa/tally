-- Slice 1R PR-3: the catalog module becomes offers (FR-013 superseded; design.md §1).
-- A one-step rename, which expand and contract (ADR-009) would normally forbid: it is safe only
-- because no production database exists. Tables, rows and constraints move with the schema.
ALTER SCHEMA catalog RENAME TO offers;
