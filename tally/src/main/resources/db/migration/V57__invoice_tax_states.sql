-- WAR-67 (ADR-050): invoice states with tax. DRAFT and TAX_QUOTED are added before PAYMENT_PENDING;
-- unissued invoices carry no issued_at. Expand and contract (ADR-009): the wider check goes in before
-- the old one goes, so the schema is never without a guard.
ALTER TABLE invoicing.invoice ALTER COLUMN issued_at DROP NOT NULL;

ALTER TABLE invoicing.invoice ADD CONSTRAINT invoice_status_check_v57
    CHECK (status IN ('DRAFT', 'TAX_QUOTED', 'PAYMENT_PENDING', 'PAID', 'PAYMENT_FAILED'));
ALTER TABLE invoicing.invoice DROP CONSTRAINT invoice_status_check;
