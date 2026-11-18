-- Slice 4 (ADR-032; WAR-84): the link from a user of the identity provider to the user's account, kept in Tally;
-- the identity provider knows nothing about accounts. One row for each user, and one user for each account.
-- "Open an account" inserts the row in the account's transaction; of two at the same time for one user, the
-- second fails on account_user_one_per_user and answers with the first one's account (CLAUDE.md rule 11).
-- Additive (ADR-009): a new table; accounts opened before it have no row.
CREATE TABLE account.account_user (
    account_id UUID        PRIMARY KEY REFERENCES account.account (id),
    user_id    TEXT        NOT NULL CONSTRAINT account_user_one_per_user UNIQUE,
    linked_at  TIMESTAMPTZ NOT NULL
);
