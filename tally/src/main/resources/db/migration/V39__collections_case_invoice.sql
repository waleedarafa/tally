-- Slice 3 PR-3 (brief rule 7; design.md section 5): one case covers every unpaid invoice of the account. A decline
-- while a case is open adds its invoice to the case; the case closes only when none of its invoices is unpaid.
--
-- Expand and contract (ADR-009): collection_case keeps invoice_id, the invoice whose decline opened the case, which
-- the previous release still reads and writes. Each case of today keeps its one invoice here: unpaid while the case
-- is open, paid when it closed. During a rolling deploy the previous release still acts on one invoice per case: a
-- second decline adds nothing, and that invoice's payment closes the case. The new release adds a case's opening
-- invoice here whenever it takes the case's lock, so a case the previous release opened gets its invoice.
CREATE TABLE collections.case_invoice (
    -- The order in which the invoices joined the case.
    position   BIGINT      GENERATED ALWAYS AS IDENTITY,
    case_id    UUID        NOT NULL REFERENCES collections.collection_case (id),
    invoice_id UUID        NOT NULL,
    joined_at  TIMESTAMPTZ NOT NULL,
    -- When the invoice was paid; null while it is unpaid.
    paid_at    TIMESTAMPTZ,
    -- An invoice joins a case once: the guard of a repeated decline (CLAUDE.md rules 7 and 11).
    CONSTRAINT case_invoice_one_per_case_and_invoice UNIQUE (case_id, invoice_id)
);

INSERT INTO collections.case_invoice (case_id, invoice_id, joined_at, paid_at)
SELECT id, invoice_id, opened_at, CASE WHEN status = 'CLOSED' THEN closed_at END
FROM collections.collection_case
ORDER BY opened_at;
