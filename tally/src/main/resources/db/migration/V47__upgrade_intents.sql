-- WAR-62 (ADR-041): an upgrade intent remembers the proration quote and the new plan between the
-- charge and the documents. The charge uses the adjustment invoice's payment reference before that
-- invoice exists, so PaymentSucceeded looks the intent up by that reference. Additive (ADR-009):
-- a new table; invoicing.invoice is not changed.
CREATE TABLE invoicing.upgrade_intent (
    payment_reference  TEXT         PRIMARY KEY,
    subscription_id    UUID         NOT NULL,
    account_id         UUID         NOT NULL,
    new_plan_id        TEXT         NOT NULL,
    new_offer_version  INT          NOT NULL CHECK (new_offer_version >= 1),
    period_invoice_id  UUID         NOT NULL REFERENCES invoicing.invoice (id),
    quote              JSONB        NOT NULL,
    completed_at       TIMESTAMPTZ
);
