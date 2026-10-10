-- Slice 1R PR-8: the highest retry Collections asked for on a collection request. Each retry is made
-- once: the request's optimistic lock tells two concurrent askers apart.
ALTER TABLE payment.collection_request ADD COLUMN retries INT NOT NULL DEFAULT 0;
