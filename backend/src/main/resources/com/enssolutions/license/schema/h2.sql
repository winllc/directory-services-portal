-- ENS license state, for JdbcLicenseStore (ens-license 1.2.0 and later).
-- Copy into the application's migrations (e.g. Flyway V<n>__ens_license_state.sql).
-- One row per product in the first two tables, so several products may share a schema.
-- H2 2.x.

CREATE TABLE ens_license_installation (
    product          VARCHAR(64)  NOT NULL PRIMARY KEY,
    installation_id  VARCHAR(32)  NOT NULL,
    created_at       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE ens_license_file (
    product          VARCHAR(64)  NOT NULL PRIMARY KEY,
    license_text     CLOB         NOT NULL,
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
