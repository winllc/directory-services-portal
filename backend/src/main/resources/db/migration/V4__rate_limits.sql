-- Per-client-address limits shared by the API replicas (failed API tokens and single sign-on
-- starts): one token bucket per key, refilled by the database's clock, updated in one statement
-- (INSERT ... ON CONFLICT DO UPDATE), so concurrent requests on any replica count once each. Rows
-- idle for two minutes are full again and are deleted by every replica's sweep.
CREATE TABLE dsp_rate_limit (
  bucket VARCHAR(200) NOT NULL,
  tokens DOUBLE PRECISION NOT NULL,
  per_minute INT NOT NULL,
  allowed BOOLEAN NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT dsp_rate_limit_pk PRIMARY KEY (bucket)
);
CREATE INDEX dsp_rate_limit_updated ON dsp_rate_limit (updated_at);
