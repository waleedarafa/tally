-- Slice 2 step 0 (D9): when an item's processing last ended, whatever the outcome, so a queue's progress can be
-- measured (tally.queue.last_progress_age_seconds). Indexed: the gauge reads max(finished_at) on every scrape.
ALTER TABLE subscription.renewal ADD COLUMN finished_at TIMESTAMPTZ;
CREATE INDEX ON subscription.renewal (finished_at);

ALTER TABLE payment.collection_queue ADD COLUMN finished_at TIMESTAMPTZ;
CREATE INDEX ON payment.collection_queue (finished_at);

ALTER TABLE collections.retry_queue ADD COLUMN finished_at TIMESTAMPTZ;
CREATE INDEX ON collections.retry_queue (finished_at);

ALTER TABLE payment.jobs ADD COLUMN finished_at TIMESTAMPTZ;
CREATE INDEX ON payment.jobs (finished_at);

ALTER TABLE invoicing.jobs ADD COLUMN finished_at TIMESTAMPTZ;
CREATE INDEX ON invoicing.jobs (finished_at);

ALTER TABLE platform.jobs ADD COLUMN finished_at TIMESTAMPTZ;
CREATE INDEX ON platform.jobs (finished_at);
