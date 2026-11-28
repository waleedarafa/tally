-- Review of #95, finding 3 (ADR-041; CLAUDE.md rules 7 and 11): two upgrades at the same time must not both
-- place an open intent for one period invoice. The guard is the named unique index below. closed_at and
-- closed_reason record an intent that ended without completing. Additive (ADR-009).
ALTER TABLE invoicing.upgrade_intent ADD COLUMN closed_at TIMESTAMPTZ NULL;
ALTER TABLE invoicing.upgrade_intent ADD COLUMN closed_reason TEXT NULL
    CONSTRAINT upgrade_intent_closed_reason CHECK (closed_reason IN ('DECLINED', 'REFUND_DUE'));
CREATE UNIQUE INDEX upgrade_intent_one_open_per_period_invoice ON invoicing.upgrade_intent (period_invoice_id)
    WHERE completed_at IS NULL AND closed_at IS NULL;
