-- WAR-66 (ADR-010): V54 added country as a nullable column and backfilled every existing row to 'US'.
-- This step requires the column and checks that it is an ISO 3166-1 alpha-2 code, so a bad value cannot be
-- written once the release that validates it is deployed.
-- contract: the previous release writes country on every account insert (Account constructors and POST /v1/accounts), and every existing row was backfilled to US in V54, so the tighten is safe here.
ALTER TABLE account.account ALTER COLUMN country SET NOT NULL;

ALTER TABLE account.account ADD CONSTRAINT account_country CHECK (country ~ '^[A-Z]{2}$');
