-- The latest time this installation has seen, for the license library's JdbcLicenseStore, so
-- setting the clock back does not bring an expired license back: epoch milliseconds, raised at
-- most hourly, and licenses are checked as of no earlier than a day before it. Null until the
-- first license check after this upgrade records one; until then the clock alone counts, as
-- before. Additive only: an instance still on the previous release ignores the column.
ALTER TABLE ens_license_installation ADD COLUMN IF NOT EXISTS latest_seen_ms BIGINT;
