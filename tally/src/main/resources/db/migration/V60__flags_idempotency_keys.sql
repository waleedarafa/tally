-- WAR-66 follow-up (ADR-053, CLAUDE.md rule 13): the flags admin API's key table, in the platform's shape (V27); the scope is the staff user (Caller.keyScope()). Additive.
CREATE TABLE flags.idempotency_key (
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
