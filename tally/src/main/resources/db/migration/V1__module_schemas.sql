-- One schema per module that owns data (CLAUDE.md rule 2, FR-003).
-- Each module creates its own tables in its own schema in later migrations.
CREATE SCHEMA account;
CREATE SCHEMA catalog;
CREATE SCHEMA subscription;
CREATE SCHEMA invoicing;
CREATE SCHEMA payment;
CREATE SCHEMA entitlement;
