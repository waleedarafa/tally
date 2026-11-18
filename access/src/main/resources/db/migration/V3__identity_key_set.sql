-- ADR-039, option A for Access (Slice 4 PR-6): the identity provider's last key set, kept in Access's own database, so an
-- instance that starts while the provider is down still checks the streaming platform's service token (D13). Written
-- on each successful fetch, read when the token decoder is built; fetched again only for a key ID it does not hold.
-- One row for each key set location.
CREATE TABLE access.identity_key_set (
    jwk_set_uri TEXT        PRIMARY KEY,
    key_set     TEXT        NOT NULL,
    fetched_at  TIMESTAMPTZ NOT NULL
);
