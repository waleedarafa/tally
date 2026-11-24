-- WAR-62 (ADR-040): the credit note. An issued invoice never changes (I1); a correction is a credit note.
-- A credit note refers to one invoice, belongs to that invoice's account, and credits in the invoice's
-- currency. The total of one credit note is greater than zero, and the total credited across one invoice
-- never exceeds that invoice: the aggregate checks the amount still available on the invoice at issue time.
-- Additive (ADR-009): new tables; invoicing.invoice is not changed.
CREATE TABLE invoicing.credit_note (
    id           UUID         PRIMARY KEY,
    invoice_id   UUID         NOT NULL,
    account_id   UUID         NOT NULL,
    amount_minor BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency     TEXT         NOT NULL,
    issued_at    TIMESTAMPTZ  NOT NULL,
    version      BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE invoicing.credit_note_line (
    credit_note_id UUID   NOT NULL REFERENCES invoicing.credit_note (id),
    line_number    INT    NOT NULL CHECK (line_number >= 0),
    description    TEXT   NOT NULL,
    amount_minor   BIGINT NOT NULL,
    currency       TEXT   NOT NULL,
    PRIMARY KEY (credit_note_id, line_number)
);

CREATE INDEX credit_note_invoice_id ON invoicing.credit_note (invoice_id);
