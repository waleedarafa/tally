-- ADR-043 (WAR-91): the billing day belongs to the account. It is set by the first subscription, from that
-- subscription's start instant in the account's time zone (the local calendar day), and it never changes.
-- Additive (ADR-009): the column is added nullable, and the CHECK goes in after the column exists, so no
-- existing row is rejected by the constraint. Accounts with no subscription stay unset (NULL) until their
-- first subscription starts.
ALTER TABLE account.account ADD COLUMN billing_day INT;
ALTER TABLE account.account ADD CONSTRAINT account_billing_day CHECK (billing_day BETWEEN 1 AND 31);

-- Existing accounts are backfilled from their earliest subscription's anchor day (ties broken by id);
-- an account with no subscription keeps a NULL billing day.
UPDATE account.account a SET billing_day = earliest.anchor_day
FROM (SELECT DISTINCT ON (account_id) account_id, anchor_day
      FROM subscription.subscription
      ORDER BY account_id, started_at ASC, id ASC) earliest
WHERE earliest.account_id = a.id;
