-- WAR-67 follow-up (ADR-051): the upgrade intent remembers the tax that was quoted for the adjustment,
-- so completion neither asks TaxRates again nor writes documents that differ from the charge.
-- Additive (ADR-009): three nullable columns on invoicing.upgrade_intent; no defaults, no backfill,
-- no DROP. An intent saved before this column has no tax and reads as a zero rate.
ALTER TABLE invoicing.upgrade_intent
    ADD COLUMN tax_rate_numerator BIGINT,
    ADD COLUMN tax_rate_denominator BIGINT,
    ADD COLUMN tax_description TEXT;

ALTER TABLE invoicing.upgrade_intent
    ADD CONSTRAINT upgrade_intent_tax_all_or_none
    CHECK (
        (tax_rate_numerator IS NULL AND tax_rate_denominator IS NULL AND tax_description IS NULL)
        OR (tax_rate_numerator IS NOT NULL AND tax_rate_denominator IS NOT NULL AND tax_description IS NOT NULL
            AND tax_rate_numerator >= 0 AND tax_rate_denominator > 0)
    );
