CREATE TABLE entitlement.access (
    account_id UUID         PRIMARY KEY,
    granted_at TIMESTAMPTZ  NOT NULL,
    granted_by TEXT         NOT NULL CHECK (granted_by IN ('SUBSCRIPTION_STARTED', 'PAYMENT_SUCCEEDED'))
);
