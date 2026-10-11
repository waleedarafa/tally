-- Slice 1R PR-9 (ADR-019, CLAUDE.md rule 13): POST /v1/subscriptions stores the client's Idempotency-Key
-- before acting, then the response with it, for 24 hours. The primary key is the guard.
CREATE TABLE subscription.idempotency_key (
    key                   TEXT        NOT NULL,
    request_hash          TEXT        NOT NULL,
    response_status       INT,
    response_content_type TEXT,
    response_body         TEXT,
    created_at            TIMESTAMPTZ NOT NULL,
    CONSTRAINT idempotency_key_pkey PRIMARY KEY (key)
);
