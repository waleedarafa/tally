CREATE TABLE account.account (
    id            UUID         PRIMARY KEY,
    currency      TEXT         NOT NULL,
    time_zone     TEXT         NOT NULL,
    payment_token TEXT,
    created_at    TIMESTAMPTZ  NOT NULL
);
