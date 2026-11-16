-- Slice 3 PR-5 ("pay now", ADR-019, CLAUDE.md rule 13): POST /v1/accounts/{accountId}/collections/payment-requests
-- stores the client's Idempotency-Key before acting, then the response with it, for 24 hours. One key table per
-- module, in the platform's shape (V27): (scope, key), the scope being the account until callers are authenticated.
CREATE TABLE collections.idempotency_key (
    scope                 TEXT        NOT NULL,
    key                   TEXT        NOT NULL,
    request_hash          TEXT        NOT NULL,
    response_status       INT,
    response_content_type TEXT,
    response_body         TEXT,
    -- When the key was first used: it is kept 24 hours from then.
    created_at            TIMESTAMPTZ NOT NULL,
    -- When the request now holding the key started: past the request timeout, it died.
    claimed_at            TIMESTAMPTZ NOT NULL,
    CONSTRAINT idempotency_key_pkey PRIMARY KEY (scope, key)
);
