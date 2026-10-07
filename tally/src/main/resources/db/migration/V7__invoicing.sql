CREATE TABLE invoicing.invoice (
    id                UUID         PRIMARY KEY,
    account_id        UUID         NOT NULL,
    subscription_id   UUID         NOT NULL,
    plan_id           TEXT         NOT NULL,
    status            TEXT         NOT NULL CHECK (status IN ('PAYMENT_PENDING', 'PAID', 'PAYMENT_FAILED')),
    amount_minor      BIGINT       NOT NULL CHECK (amount_minor >= 0),
    currency          TEXT         NOT NULL,
    period_start      DATE         NOT NULL,
    period_end        DATE         NOT NULL CHECK (period_end > period_start),
    payment_reference TEXT         NOT NULL UNIQUE,
    issued_at         TIMESTAMPTZ  NOT NULL,
    paid_at           TIMESTAMPTZ,
    -- D1: exactly one invoice per account and billing date. The final guard against duplicates.
    CONSTRAINT invoice_one_per_account_and_period UNIQUE (account_id, period_start)
);
