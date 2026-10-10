-- Slice 1R PR-4 (ADR-015): offers with published versions, and kinds of offer. The first run's three
-- plans become version 1 of offers of kind fixed-price-phase, at the same prices. The old plan tables
-- are dropped in the same step, which expand and contract (ADR-009) would normally forbid: it is safe
-- only because no production database exists.

CREATE TABLE offers.offer_kind (
    id     TEXT PRIMARY KEY,
    status TEXT NOT NULL CHECK (status IN ('EXPERIMENTAL', 'APPROVED'))
);

CREATE TABLE offers.offer (
    id   TEXT PRIMARY KEY,
    name TEXT NOT NULL
);

-- O2: a published version is never updated; a change inserts the next version.
CREATE TABLE offers.offer_version (
    offer_id     TEXT        NOT NULL REFERENCES offers.offer (id),
    version      INT         NOT NULL CHECK (version >= 1),
    kind         TEXT        NOT NULL REFERENCES offers.offer_kind (id),
    free_periods INT         NOT NULL DEFAULT 0 CHECK (free_periods >= 0),
    published_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (offer_id, version)
);

CREATE TABLE offers.offer_version_price (
    offer_id     TEXT   NOT NULL,
    version      INT    NOT NULL,
    currency     TEXT   NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor >= 0),
    PRIMARY KEY (offer_id, version, currency),
    FOREIGN KEY (offer_id, version) REFERENCES offers.offer_version (offer_id, version)
);

-- Both kinds have a rule. Fixed price is approved: the first run sold it. Free trial waits for finance (O1).
INSERT INTO offers.offer_kind (id, status) VALUES
    ('fixed-price-phase', 'APPROVED'),
    ('free-trial', 'EXPERIMENTAL');

INSERT INTO offers.offer (id, name) SELECT id, name FROM offers.plan;

INSERT INTO offers.offer_version (offer_id, version, kind, published_at)
    SELECT id, 1, 'fixed-price-phase', TIMESTAMPTZ '2026-01-01 00:00:00+00' FROM offers.plan;

INSERT INTO offers.offer_version_price (offer_id, version, currency, amount_minor)
    SELECT plan_id, 1, currency, amount_minor FROM offers.plan_price WHERE billing_period = 'MONTHLY';

DROP TABLE offers.plan_price;
DROP TABLE offers.plan;
