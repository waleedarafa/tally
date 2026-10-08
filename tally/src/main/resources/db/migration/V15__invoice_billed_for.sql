-- D6, ADR-025: when each invoice was due to be billed, next to issued_at (when it was issued).
-- Lateness is issued_at minus billed_for, measurable after the billing queue's rows are deleted.
-- Nullable while instances of the previous release, which do not set it, may still run (ADR-007);
-- a later release backfills again and makes it NOT NULL.
ALTER TABLE invoicing.invoice ADD COLUMN billed_for TIMESTAMPTZ;

-- Backfill, from Invoicing's own data only (rule 2):
-- - a subscription's first invoice was billed at subscribe: billed_for = issued_at;
-- - a renewal was due when its billing item fell due (the item's key is account and billing date);
-- - a renewal whose billing item retention already deleted falls back to issued_at.
UPDATE invoicing.invoice i SET billed_for = s.due_at
FROM invoicing.billing_schedule s
WHERE s.queue_key = i.account_id::text || ':' || i.period_start::text
  AND i.billed_for IS NULL;

UPDATE invoicing.invoice SET billed_for = issued_at WHERE billed_for IS NULL;
