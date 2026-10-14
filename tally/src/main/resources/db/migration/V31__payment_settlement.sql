-- Settlement reconciliation (proposed 021, decided 2026-10-14; D14). Payment reads the provider's settlement
-- batches and compares them with its attempts, both ways. Expand only: three new tables.

-- Each batch is imported once, with all its lines, in one transaction. The highest number is where the next
-- import starts.
CREATE TABLE payment.settlement_batch (
    number      BIGINT       NOT NULL,
    imported_at TIMESTAMPTZ  NOT NULL,
    lines       INT          NOT NULL,
    CONSTRAINT settlement_batch_imported_once PRIMARY KEY (number)
);

-- A charge whose money the provider moved. A charge settles once.
CREATE TABLE payment.settlement_line (
    charge_id    TEXT    NOT NULL,
    batch        BIGINT  NOT NULL REFERENCES payment.settlement_batch (number),
    reference    TEXT    NOT NULL,
    amount_minor BIGINT  NOT NULL,
    currency     TEXT    NOT NULL,
    CONSTRAINT settlement_line_one_per_charge PRIMARY KEY (charge_id)
);
CREATE INDEX settlement_line_by_reference ON payment.settlement_line (reference);

-- What a person must look at. The subject is the charge ID for a mismatch found in a batch, the attempt ID for one
-- found in Tally's attempts. Found once per kind and subject; closed when its cause goes away or a person decides.
CREATE TABLE payment.settlement_mismatch (
    id          UUID         PRIMARY KEY,
    kind        TEXT         NOT NULL,
    subject     TEXT         NOT NULL,
    reference   TEXT         NOT NULL,
    detail      TEXT         NOT NULL,
    found_at    TIMESTAMPTZ  NOT NULL,
    resolved_at TIMESTAMPTZ,
    CONSTRAINT settlement_mismatch_found_once UNIQUE (kind, subject)
);
CREATE INDEX settlement_mismatch_open ON payment.settlement_mismatch (kind) WHERE resolved_at IS NULL;
