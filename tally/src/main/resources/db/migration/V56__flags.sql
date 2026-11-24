-- WAR-66 (ADR-010, ADR-048): the flags catalogue lives in its own schema. A flag is defined off by default, assigned per account or country, with a change log, and may be fully released. Additive.
CREATE SCHEMA flags;

CREATE TABLE flags.flag (
    name        TEXT        PRIMARY KEY,
    default_on  BOOLEAN     NOT NULL,
    released_at TIMESTAMPTZ NULL,
    CONSTRAINT flag_name CHECK (name ~ '^[a-z][a-z0-9-]*$')
);

CREATE TABLE flags.account_assignment (
    flag_name  TEXT    NOT NULL REFERENCES flags.flag (name) ON DELETE CASCADE,
    account_id UUID    NOT NULL,
    is_on      BOOLEAN NOT NULL,
    PRIMARY KEY (flag_name, account_id)
);

CREATE TABLE flags.country_assignment (
    flag_name TEXT    NOT NULL REFERENCES flags.flag (name) ON DELETE CASCADE,
    country   TEXT    NOT NULL,
    is_on     BOOLEAN NOT NULL,
    PRIMARY KEY (flag_name, country),
    CONSTRAINT country_assignment_country CHECK (country ~ '^[A-Z]{2}$')
);

CREATE TABLE flags.flag_change (
    flag_name   TEXT        NOT NULL REFERENCES flags.flag (name) ON DELETE CASCADE,
    seq         BIGINT      NOT NULL,
    at          TIMESTAMPTZ NOT NULL,
    description TEXT        NOT NULL,
    PRIMARY KEY (flag_name, seq)
);
