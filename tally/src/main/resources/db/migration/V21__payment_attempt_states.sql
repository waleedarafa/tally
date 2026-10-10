-- Slice 1R PR-6 (ADR-013, ADR-018): payment attempts move to the second design's six states.
--   PENDING -> REQUESTED, IN_FLIGHT -> SENT, FAILED -> DECLINED (the reason kept: a soft decline is still
--   the bank's answer about the card), SUCCEEDED and UNKNOWN unchanged.
-- No column tells a first-run "no such charge" row (reconciliation recorded it as FAILED, reason SOFT)
-- from a real soft decline: both have no charge_id and the same reconciliation columns. So every FAILED
-- row becomes DECLINED. This is safe only because no production database exists; with real data, those
-- rows would need the provider's record before migrating.

ALTER TABLE payment.payment_attempt DROP CONSTRAINT payment_attempt_state_check;
DROP INDEX payment.payment_attempt_one_open_per_reference;

UPDATE payment.payment_attempt SET state = CASE state
    WHEN 'PENDING' THEN 'REQUESTED'
    WHEN 'IN_FLIGHT' THEN 'SENT'
    WHEN 'FAILED' THEN 'DECLINED'
    ELSE state END;

ALTER TABLE payment.payment_attempt ADD CONSTRAINT payment_attempt_state_check
    CHECK (state IN ('REQUESTED', 'SENT', 'SUCCEEDED', 'DECLINED', 'TECHNICAL_FAILURE', 'UNKNOWN'));

-- P2: no new attempt while one is REQUESTED, SENT or UNKNOWN.
CREATE UNIQUE INDEX payment_attempt_one_open_per_reference
    ON payment.payment_attempt (reference) WHERE state IN ('REQUESTED', 'SENT', 'UNKNOWN');

-- When the attempt was committed as sent: the stale rule (ADR-018) and the no-charge rule (proposed
-- ADR-026) measure from it. Best known for existing rows: their last change.
ALTER TABLE payment.payment_attempt ADD COLUMN sent_at TIMESTAMPTZ;
UPDATE payment.payment_attempt SET sent_at = updated_at WHERE state <> 'REQUESTED';
CREATE INDEX payment_attempt_sent ON payment.payment_attempt (sent_at) WHERE state = 'SENT';

-- The Payment aggregate's version, the sequence its events will carry (ADR-016).
ALTER TABLE payment.collection_request ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Each attempt's number within its request: 1, then one more for each retry after a technical failure.
-- The latest attempt is the highest number (timestamps can tie). The default keeps an older release's
-- inserts, which never retry, valid during a rolling update (FR-025).
ALTER TABLE payment.payment_attempt ADD COLUMN number INT NOT NULL DEFAULT 1;
UPDATE payment.payment_attempt a SET number = n.n
FROM (SELECT id, row_number() OVER (PARTITION BY reference ORDER BY created_at, id)::int AS n
      FROM payment.payment_attempt) n
WHERE n.id = a.id;
ALTER TABLE payment.payment_attempt ADD CONSTRAINT payment_attempt_one_per_number UNIQUE (reference, number);
