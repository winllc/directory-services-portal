-- ENS license state in the shared database (ens-license 1.2.0 JdbcLicenseStore): the installation
-- id, the license and the highest revisions seen, shared by every replica. Copied from the
-- library's schema/postgresql.sql.

CREATE TABLE ens_license_installation (
    product          VARCHAR(64)  NOT NULL PRIMARY KEY,
    installation_id  VARCHAR(32)  NOT NULL,
    created_at       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE ens_license_file (
    product          VARCHAR(64)  NOT NULL PRIMARY KEY,
    license_text     TEXT         NOT NULL,
    license_version  BIGINT       NOT NULL,
    updated_at       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE ens_license_revision (
    product          VARCHAR(64)  NOT NULL,
    license_id       VARCHAR(128) NOT NULL,
    revision         INTEGER      NOT NULL,
    updated_at       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY (product, license_id)
);

-- A database already in use before this migration (an upgrade) is marked: an instance that then
-- finds no installation id here and none to import refuses to start instead of making a new one
-- (see LicenseStateGuard). A new database gets both migrations at once, while it is still empty.
INSERT INTO dsp_document (name, version, content)
SELECT 'license/database-upgraded', 1, '"V2 applied to a database in use"'
WHERE EXISTS (SELECT 1 FROM dsp_document) OR EXISTS (SELECT 1 FROM dsp_audit_event) OR EXISTS (SELECT 1 FROM spring_session);
