-- Slice 1R PR-8 (CONTRACT.md A22, A23; proposed ADR-026): each attempt sends its own provider key, the
-- reference plus the attempt number, stored with the attempt's parameters (token, amount, currency and
-- reference, already columns), which a repeat by key must match exactly.
--
-- Existing attempts get the key their number gives them. The provider never saw those keys: a repeat
-- with one would be a new charge, not a status query. Safe only because no production data exists.
ALTER TABLE payment.payment_attempt ADD COLUMN idempotency_key TEXT;
UPDATE payment.payment_attempt SET idempotency_key = reference || ':' || number;
ALTER TABLE payment.payment_attempt ALTER COLUMN idempotency_key SET NOT NULL;
ALTER TABLE payment.payment_attempt ADD CONSTRAINT payment_attempt_one_per_key UNIQUE (idempotency_key);
