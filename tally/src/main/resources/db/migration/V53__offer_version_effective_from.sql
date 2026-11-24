-- ADR-047 (WAR-65): an offer version takes effect on a date. effective_from is a local date, set
-- when the version is published and never changed; published_at remains the instant of publication.
-- Additive (ADR-009): the column is added nullable, backfilled, and only then required, so no
-- existing row is rejected while the column is being filled. Publishing on 20 October with an
-- effective date of 1 November leaves the previous version current through 31 October.
--
-- Existing rows are backfilled with the UTC date of published_at; there is no catalogue time zone,
-- so UTC is the only honest date of an instant. This replaces the temporary UTC mapping in
-- JdbcOffers (the derivation from published_at for the missing column).
ALTER TABLE offers.offer_version ADD COLUMN effective_from DATE NULL;

UPDATE offers.offer_version SET effective_from = (published_at AT TIME ZONE 'UTC')::date;

-- contract: the previous release only reads offer_version (JdbcOffers does not insert), and every existing row is backfilled in this same migration, so the tighten is safe here.
ALTER TABLE offers.offer_version ALTER COLUMN effective_from SET NOT NULL;
