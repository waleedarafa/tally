-- the credit, VAT included, is capped at this amount (ADR-060); null = no cap
ALTER TABLE invoicing.upgrade_intent ADD COLUMN credit_cap_minor BIGINT;
