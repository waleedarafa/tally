-- WAR-62 (ADR-040): the credit note's invoice_id is a real reference, and the per-invoice credit cap
-- is enforced by the database, not only at issue time. Additive (ADR-009): the tables are not
-- changed, only their constraints and a trigger are added.
ALTER TABLE invoicing.credit_note
    ADD CONSTRAINT credit_note_invoice_id_refs
    FOREIGN KEY (invoice_id) REFERENCES invoicing.invoice (id);

-- The cap: the total of the credit notes against one invoice never exceeds that invoice's amount.
-- Asserted here, at every insert and amount/invoice change, so a caller that passes an "available"
-- that is larger than the invoice's true remaining amount is still refused.
CREATE FUNCTION invoicing.credit_note_cap_check() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF (SELECT sum(amount_minor)
        FROM invoicing.credit_note
        WHERE invoice_id = NEW.invoice_id)
        > (SELECT amount_minor
           FROM invoicing.invoice
           WHERE id = NEW.invoice_id) THEN
        RAISE EXCEPTION 'credit notes against invoice % total %, which exceeds the invoice amount',
            NEW.invoice_id,
            (SELECT sum(amount_minor)
             FROM invoicing.credit_note
             WHERE invoice_id = NEW.invoice_id)
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER credit_note_cap_trigger
AFTER INSERT OR UPDATE OF invoice_id, amount_minor ON invoicing.credit_note
DEFERRABLE INITIALLY IMMEDIATE
FOR EACH ROW EXECUTE FUNCTION invoicing.credit_note_cap_check();
