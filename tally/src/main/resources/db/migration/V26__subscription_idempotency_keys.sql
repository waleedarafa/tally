-- Slice 1R PR-9 (ADR-019, CLAUDE.md rule 13): POST /v1/subscriptions stores the client's Idempotency-Key
-- before acting, then the response with it, for 24 hours. Keys are scoped by account until callers are
-- authenticated (then by caller). The primary key is the guard.
CREATE TABLE subscription.idempotency_key (
    account_id            UUID        NOT NULL,
    key                   TEXT        NOT NULL,
    request_hash          TEXT        NOT NULL,
    response_status       INT,
    response_content_type TEXT,
    response_body         TEXT,
    -- When the key was first used: it is kept 24 hours from then.
    created_at            TIMESTAMPTZ NOT NULL,
    -- When the request now holding the key started: past the request timeout, it died.
    claimed_at            TIMESTAMPTZ NOT NULL,
    CONSTRAINT idempotency_key_pkey PRIMARY KEY (account_id, key)
);
