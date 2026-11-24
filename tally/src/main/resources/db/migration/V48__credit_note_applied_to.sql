-- WAR-62: a credit note corrects one invoice (invoice_id) and may be applied to another when it
-- settles a later charge. For an upgrade, the note corrects the paid period invoice (ADR-042) and
-- is applied to the adjustment invoice (the new-plan charge), so invoiced-versus-collected can see
-- that the adjustment was paid by the credit plus the net charge. Additive (ADR-009).
ALTER TABLE invoicing.credit_note
    ADD COLUMN applied_to_invoice_id UUID REFERENCES invoicing.invoice (id);
