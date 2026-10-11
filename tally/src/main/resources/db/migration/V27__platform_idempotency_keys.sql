-- Slice 1R PR-9b: idempotency keys move to the platform's shared mechanism (one table per module, like the
-- durable queues; CLAUDE.md rule 1). The table shape is platform's: (scope, key), scope being the account
-- until callers are authenticated. One-step change: the stored keys keep their account as their scope.
ALTER TABLE subscription.idempotency_key DROP CONSTRAINT idempotency_key_pkey;
ALTER TABLE subscription.idempotency_key RENAME COLUMN account_id TO scope;
ALTER TABLE subscription.idempotency_key ALTER COLUMN scope TYPE TEXT USING scope::text;
ALTER TABLE subscription.idempotency_key ADD CONSTRAINT idempotency_key_pkey PRIMARY KEY (scope, key);

-- POST /v1/accounts (rule 13). No account exists before the request, so its keys share one scope until
-- callers are authenticated.
CREATE TABLE account.idempotency_key (
    scope                 TEXT        NOT NULL,
    key                   TEXT        NOT NULL,
    request_hash          TEXT        NOT NULL,
    response_status       INT,
    response_content_type TEXT,
    response_body         TEXT,
    created_at            TIMESTAMPTZ NOT NULL,
    claimed_at            TIMESTAMPTZ NOT NULL,
    CONSTRAINT idempotency_key_pkey PRIMARY KEY (scope, key)
);
