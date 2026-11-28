-- WAR-66 (ADR-010): per-country flags need a country on the account (ISO 3166-1 alpha-2). It is set at open and is never inferred from time zone or currency (UTC and USD are shared by many countries). Additive (ADR-009): this step only adds the column nullable and backfills it; a later step will require it once the application writes it.
ALTER TABLE account.account ADD COLUMN country TEXT NULL;

UPDATE account.account SET country = 'US' WHERE country IS NULL;
