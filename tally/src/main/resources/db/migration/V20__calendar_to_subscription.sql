-- Slice 1R PR-5 (Design Record §12, ADR-003): the billing calendar moves from Invoicing to Subscription.
-- Subscription owns periods; Invoicing bills the period each SubscriptionChanged carries.
-- One step: Invoicing's calendar tables are read once here and dropped, which expand and contract
-- (ADR-009) and rule 2 (no module reads another's tables) would normally forbid. It is safe only because
-- no production database exists.

-- The subscription's calendar anchor and its pinned offer version (decision A: every existing
-- subscription signed up to version 1, the only version before this release).
ALTER TABLE subscription.subscription ADD COLUMN anchor_day INT;
ALTER TABLE subscription.subscription ADD COLUMN offer_version INT;
UPDATE subscription.subscription s SET
    anchor_day = coalesce((SELECT b.anchor_day FROM invoicing.subscription_billing b WHERE b.subscription_id = s.id),
                          EXTRACT(DAY FROM s.started_at)::int),
    offer_version = 1;
ALTER TABLE subscription.subscription ALTER COLUMN anchor_day SET NOT NULL;
ALTER TABLE subscription.subscription ADD CONSTRAINT subscription_anchor_day CHECK (anchor_day BETWEEN 1 AND 31);
ALTER TABLE subscription.subscription ALTER COLUMN offer_version SET NOT NULL;

-- Every period of every subscription. The unique constraint is the renewal queue's permanent guard
-- (FR-037): a period is created once, so a renewal is done once.
CREATE TABLE subscription.subscription_period (
    subscription_id UUID NOT NULL REFERENCES subscription.subscription (id),
    position        INT  NOT NULL CHECK (position >= 0),
    number          INT  NOT NULL CHECK (number >= 1),
    start_date      DATE NOT NULL,
    end_date        DATE NOT NULL CHECK (end_date > start_date),
    PRIMARY KEY (subscription_id, position),
    CONSTRAINT subscription_period_one_per_number UNIQUE (subscription_id, number)
);

-- Existing periods are the periods already invoiced, in order.
INSERT INTO subscription.subscription_period (subscription_id, position, number, start_date, end_date)
SELECT subscription_id, n - 1, n, period_start, period_end
FROM (SELECT subscription_id, period_start, period_end,
             row_number() OVER (PARTITION BY subscription_id ORDER BY period_start)::int AS n
      FROM invoicing.invoice) invoiced
WHERE subscription_id IN (SELECT id FROM subscription.subscription);

-- The renewal queue (FR-017). Shape documented on QueueDefinition.
CREATE TABLE subscription.renewal (
    id              BIGSERIAL    PRIMARY KEY,
    queue_key       TEXT         NOT NULL UNIQUE,
    due_at          TIMESTAMPTZ  NOT NULL,
    payload         TEXT         NOT NULL,
    status          TEXT         NOT NULL CHECK (status IN ('READY', 'IN_PROCESSING', 'DONE', 'FAILED')),
    attempts        INT          NOT NULL DEFAULT 0,
    claim_token     UUID,
    claimed_at      TIMESTAMPTZ,
    claimed_by      TEXT,
    last_error      TEXT,
    last_success_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ON subscription.renewal (due_at) WHERE status IN ('READY', 'IN_PROCESSING');

-- Renewals not yet done move across, renumbered as the period after the subscription's latest.
INSERT INTO subscription.renewal (queue_key, due_at, payload, status, attempts, created_at)
SELECT q.queue_key, q.due_at,
       split_part(q.payload, ',', 1) || ',' ||
           (coalesce((SELECT max(p.number) FROM subscription.subscription_period p
                      WHERE p.subscription_id = split_part(q.payload, ',', 1)::uuid), 0) + 1),
       'READY', 0, q.created_at
FROM invoicing.billing_schedule q
WHERE q.status IN ('READY', 'IN_PROCESSING', 'FAILED');

DROP TABLE invoicing.billing_schedule;
DROP TABLE invoicing.subscription_billing;

-- I2: at most one invoice per subscription and period number, which the invoice ID now encodes.
ALTER TABLE invoicing.invoice ADD COLUMN period_number INT;
UPDATE invoicing.invoice i SET period_number = n.n
FROM (SELECT id, row_number() OVER (PARTITION BY subscription_id ORDER BY period_start)::int AS n
      FROM invoicing.invoice) n
WHERE n.id = i.id;
ALTER TABLE invoicing.invoice DROP CONSTRAINT invoice_one_per_account_and_period;
ALTER TABLE invoicing.invoice ADD CONSTRAINT invoice_one_per_subscription_and_period
    UNIQUE (subscription_id, period_number);
