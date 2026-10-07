-- The fixed slice 1 catalogue: three monthly plans, each priced in AED and USD (minor units).
CREATE TABLE catalog.plan (
    id   TEXT PRIMARY KEY,
    name TEXT NOT NULL
);

CREATE TABLE catalog.plan_price (
    plan_id        TEXT    NOT NULL REFERENCES catalog.plan (id),
    billing_period TEXT    NOT NULL CHECK (billing_period IN ('MONTHLY')),
    currency       TEXT    NOT NULL,
    amount_minor   BIGINT  NOT NULL CHECK (amount_minor >= 0),
    PRIMARY KEY (plan_id, billing_period, currency)
);

INSERT INTO catalog.plan (id, name) VALUES
    ('BASIC', 'Basic'),
    ('STANDARD', 'Standard'),
    ('PREMIUM', 'Premium');

INSERT INTO catalog.plan_price (plan_id, billing_period, currency, amount_minor) VALUES
    ('BASIC',    'MONTHLY', 'USD',   999),
    ('BASIC',    'MONTHLY', 'AED',  3699),
    ('STANDARD', 'MONTHLY', 'USD',  1999),
    ('STANDARD', 'MONTHLY', 'AED',  7299),
    ('PREMIUM',  'MONTHLY', 'USD',  2999),
    ('PREMIUM',  'MONTHLY', 'AED', 10999);
