-- Slice 1R PR-4 (I3, D7): an invoice carries its PriceQuote's lines, the offer version priced and the
-- quote's reason. Additive: invoices issued before have no lines, and null version and reason.
ALTER TABLE invoicing.invoice ADD COLUMN offer_version INT;
ALTER TABLE invoicing.invoice ADD COLUMN quote_reason TEXT;

CREATE TABLE invoicing.invoice_line (
    invoice_id   UUID   NOT NULL REFERENCES invoicing.invoice (id),
    line_number  INT    NOT NULL CHECK (line_number >= 0),
    description  TEXT   NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency     TEXT   NOT NULL,
    PRIMARY KEY (invoice_id, line_number)
);
