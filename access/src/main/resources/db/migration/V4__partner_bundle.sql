-- ADR-056: partner bundles, one row per partner and bundle, the highest partner sequence wins; not rebuilt from the log
-- (Access holds the only record of what partners sent). A repeat or an older notice changes nothing, so an ended
-- bundle stays ended.
CREATE TABLE access.partner_bundle (
    partner_id       TEXT        NOT NULL,
    bundle_id        TEXT        NOT NULL,
    subscriber_id    UUID        NOT NULL,
    state            TEXT        NOT NULL CHECK (state IN ('ACTIVE', 'ENDED')),
    partner_sequence BIGINT      NOT NULL CHECK (partner_sequence >= 1),
    occurred_at      TIMESTAMPTZ NOT NULL,
    applied_at       TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (partner_id, bundle_id)
);
CREATE INDEX partner_bundle_subscriber ON access.partner_bundle (subscriber_id);
