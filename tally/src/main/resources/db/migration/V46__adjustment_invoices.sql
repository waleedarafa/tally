-- WAR-62 (ADR-040): the adjustment invoice. The I2 amendment makes the (subscription_id, period_number)
-- pair no longer identify one document: an adjustment shares it with its period invoice. This adds the
-- column that tells the two apart ('PERIOD' or 'ADJUSTMENT') and the adjustment's tie to the period
-- invoice (its id). Expand and contract (ADR-009), like V44: the new guard is created before the old one
-- is dropped, so the table is never without one. Existing rows are period invoices: the default fills
-- 'PERIOD'. PostgreSQL places a created index in the table's schema, so the index names fall under
-- invoicing with this table; all existing migrations here follow the same unqualified-name convention.
ALTER TABLE invoicing.invoice ADD COLUMN invoice_type TEXT NOT NULL DEFAULT 'PERIOD';
ALTER TABLE invoicing.invoice ADD COLUMN period_invoice_id UUID;
ALTER TABLE invoicing.invoice ADD CONSTRAINT invoice_type_check
    CHECK (invoice_type IN ('PERIOD', 'ADJUSTMENT'));
ALTER TABLE invoicing.invoice ADD CONSTRAINT invoice_period_invoice_tie_check
    CHECK ((invoice_type = 'PERIOD' AND period_invoice_id IS NULL)
        OR (invoice_type = 'ADJUSTMENT' AND period_invoice_id IS NOT NULL));
ALTER TABLE invoicing.invoice
    ADD CONSTRAINT invoice_period_invoice_id_refs
    FOREIGN KEY (period_invoice_id) REFERENCES invoicing.invoice (id);

-- At most one period invoice per subscription and period; the old blanket constraint is dropped only
-- once this guard exists, and never while the table is without one.
CREATE UNIQUE INDEX invoice_one_period_per_subscription_and_period
    ON invoicing.invoice (subscription_id, period_number) WHERE invoice_type = 'PERIOD';

-- Look a period invoice up by adjustment (D7).
CREATE INDEX invoice_period_invoice_id
    ON invoicing.invoice (period_invoice_id) WHERE period_invoice_id IS NOT NULL;

ALTER TABLE invoicing.invoice DROP CONSTRAINT invoice_one_per_subscription_and_period;
