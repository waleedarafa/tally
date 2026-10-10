-- FR-041: one collection request per reference. The request is never deleted, so its unique
-- constraint is Payment's permanent guard against a second charge for one reference (FR-037).
-- Additive (FR-025): payment_attempt.request_id stays nullable while instances of the previous
-- release, which do not set it, may still be running (FR-007). A later release backfills again
-- and makes it NOT NULL.
CREATE TABLE payment.collection_request (
    id            UUID         PRIMARY KEY,
    reference     TEXT         NOT NULL CONSTRAINT collection_request_one_per_reference UNIQUE,
    account_id    UUID         NOT NULL,
    amount_minor  BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency      TEXT         NOT NULL,
    payment_token TEXT         NOT NULL,
    outcome       TEXT         NOT NULL CHECK (outcome IN ('OPEN', 'SUCCEEDED', 'FAILED')),
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);

ALTER TABLE payment.payment_attempt ADD COLUMN request_id UUID REFERENCES payment.collection_request (id);
CREATE INDEX payment_attempt_by_request ON payment.payment_attempt (request_id);

-- Backfill: one request per reference, from its earliest attempt; the outcome from its latest.
INSERT INTO payment.collection_request
    (id, reference, account_id, amount_minor, currency, payment_token, outcome, created_at, updated_at)
SELECT gen_random_uuid(), first.reference, first.account_id, first.amount_minor, first.currency,
       first.payment_token,
       CASE latest.state WHEN 'SUCCEEDED' THEN 'SUCCEEDED' WHEN 'FAILED' THEN 'FAILED' ELSE 'OPEN' END,
       first.created_at, latest.updated_at
FROM (SELECT DISTINCT ON (reference) * FROM payment.payment_attempt ORDER BY reference, created_at, id) first
JOIN (SELECT DISTINCT ON (reference) * FROM payment.payment_attempt ORDER BY reference, created_at DESC, id DESC) latest
    ON latest.reference = first.reference;

UPDATE payment.payment_attempt a SET request_id = r.id
FROM payment.collection_request r
WHERE r.reference = a.reference AND a.request_id IS NULL;
