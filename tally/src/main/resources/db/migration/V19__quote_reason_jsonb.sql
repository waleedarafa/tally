-- The quote's reason as data, so ADR-010's flag report can query which invoices a flag priced (D7).
-- Every invoice issued since V18 was priced by kind fixed-price-phase with no flags (the only approved
-- kind), so its reason is rebuilt exactly from the plan and offer version.
ALTER TABLE invoicing.invoice ALTER COLUMN quote_reason TYPE jsonb USING
    CASE WHEN quote_reason IS NULL THEN NULL
         ELSE jsonb_build_object('offerId', plan_id, 'offerVersion', offer_version,
                                 'kind', 'fixed-price-phase', 'flags', '{}'::jsonb) END;
