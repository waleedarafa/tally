-- Record of a correction to V44's header comment (see V44__subscription_live_states.sql): V44's header
-- claimed that "The new CHECK goes in before the old one goes". That is not what the script did: the script
-- drops CONSTRAINT subscription_status_check first and then adds the wider CHECK,
-- so the schema is briefly without a guard. Only the index half was in the right order (the new
-- index is created before the old one is dropped). V44 is not edited: Flyway checksums already-applied
-- migrations, so editing it would fail checksum validation; this note records the fact instead.
-- The customer cancel that ends at the period boundary (design.md §5: CANCELLING → ENDED) writes CANCELLED as
-- the end reason. Expand and contract (ADR-009): the wider check goes in before the old one goes, so the schema
-- is never without a guard. No reason other than the allowed set is written until the old check is dropped.
ALTER TABLE subscription.subscription ADD CONSTRAINT subscription_end_reason_check_v50
    CHECK (end_reason IN ('WRITE_OFF', 'FIRST_PAYMENT_DECLINED', 'CANCELLED'));
ALTER TABLE subscription.subscription DROP CONSTRAINT subscription_end_reason_check;
