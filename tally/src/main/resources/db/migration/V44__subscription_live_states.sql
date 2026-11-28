-- Slice 3 (WAR-60 close-out; design.md section 5, ADR-009): TRIAL and CANCELLING are live states, so a
-- household in one of them still signs up alone. Expand and contract, stated plainly: the previous release reads
-- the status with JPA's valueOf, so for the length of a rolling deploy it fails on a row in TRIAL or CANCELLING.
-- The new CHECK goes in before the old one goes, and the new index goes before the old one is dropped, so the
-- schema is never without a guard.
ALTER TABLE subscription.subscription DROP CONSTRAINT subscription_status_check;
ALTER TABLE subscription.subscription ADD CONSTRAINT subscription_status_check
    CHECK (status IN ('TRIAL', 'ACTIVE', 'CANCELLING', 'ENDED'));

-- The sign-up's duplicate guard (CLAUDE.md rule 11) now covers every live state. The old index,
-- WHERE status = 'ACTIVE', is dropped after the new one exists, so an account never has neither.
CREATE UNIQUE INDEX subscription_one_live_subscription_per_account ON subscription.subscription (account_id)
    WHERE status IN ('TRIAL', 'ACTIVE', 'CANCELLING');
DROP INDEX subscription.subscription_one_live_per_account;
