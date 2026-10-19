-- The shared store of the Directory Services Portal (DSP_DATABASE_URL): what several API replicas
-- must see alike. Applied by Flyway at startup; never edit a released migration, add a new one.

-- Signed-in sessions (Spring Session JDBC, its PostgreSQL schema). PRINCIPAL_NAME holds the
-- SHA-256 of the user's DN, so the per-user session limit can find a user's sessions.
CREATE TABLE spring_session (
  primary_id CHAR(36) NOT NULL,
  session_id CHAR(36) NOT NULL,
  creation_time BIGINT NOT NULL,
  last_access_time BIGINT NOT NULL,
  max_inactive_interval INT NOT NULL,
  expiry_time BIGINT NOT NULL,
  principal_name VARCHAR(100),
  CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
  session_primary_id CHAR(36) NOT NULL,
  attribute_name VARCHAR(200) NOT NULL,
  attribute_bytes BYTEA NOT NULL,
  CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
  CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id) REFERENCES spring_session (primary_id) ON DELETE CASCADE
);

-- Cluster locks for scheduled work (ShedLock): one replica runs each job at a time.
CREATE TABLE dsp_shedlock (
  name VARCHAR(64) NOT NULL,
  lock_until TIMESTAMP NOT NULL,
  locked_at TIMESTAMP NOT NULL,
  locked_by VARCHAR(255) NOT NULL,
  CONSTRAINT dsp_shedlock_pk PRIMARY KEY (name)
);

-- Versioned JSON documents: the portal configuration, saved analysis results, audit API tokens and
-- the audit forwarders' cursors. A write must name the version it replaces.
CREATE TABLE dsp_document (
  name VARCHAR(200) NOT NULL,
  version BIGINT NOT NULL,
  content TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT dsp_document_pk PRIMARY KEY (name)
);

-- The audit store: one ens-audit/1 line per event, hash-chained in seq order exactly as in the
-- monthly files (appends are serialized with a transaction-level advisory lock).
CREATE TABLE dsp_audit_event (
  seq BIGINT NOT NULL,
  event_month CHAR(7) NOT NULL,
  line TEXT NOT NULL,
  CONSTRAINT dsp_audit_event_pk PRIMARY KEY (seq)
);

-- Failed password sign-ins per (client address, user name), as SHA-256: the lockout counts them
-- across replicas.
CREATE TABLE dsp_login_failure (
  throttle_key CHAR(64) NOT NULL,
  failures INT NOT NULL,
  first_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT dsp_login_failure_pk PRIMARY KEY (throttle_key)
);

-- Single sign-on requests in flight (OIDC authorization requests, SAML AuthnRequests), each used
-- once, and the SAML assertion ids already accepted (replay protection).
CREATE TABLE dsp_sso_request (
  id CHAR(64) NOT NULL,
  payload BYTEA NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT dsp_sso_request_pk PRIMARY KEY (id)
);
CREATE INDEX dsp_sso_request_expiry ON dsp_sso_request (expires_at);

CREATE TABLE dsp_saml_assertion (
  id VARCHAR(256) NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT dsp_saml_assertion_pk PRIMARY KEY (id)
);
CREATE INDEX dsp_saml_assertion_expiry ON dsp_saml_assertion (expires_at);

-- Background computations (analyses, slow metrics): their progress and results, so any replica
-- can show them; the replica running one acts on a cancel request.
CREATE TABLE dsp_job (
  id VARCHAR(64) NOT NULL,
  owner VARCHAR(256) NOT NULL,
  replica VARCHAR(128) NOT NULL,
  view_json TEXT NOT NULL,
  result_json TEXT,
  status VARCHAR(16) NOT NULL,
  background BOOLEAN NOT NULL DEFAULT false,
  cancel_requested BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at TIMESTAMPTZ,
  CONSTRAINT dsp_job_pk PRIMARY KEY (id)
);
CREATE INDEX dsp_job_owner ON dsp_job (owner);
