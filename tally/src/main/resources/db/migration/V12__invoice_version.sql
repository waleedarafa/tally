-- Optimistic lock: a listener may run twice at the same time (Spike A, finding 3).
ALTER TABLE invoicing.invoice ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
