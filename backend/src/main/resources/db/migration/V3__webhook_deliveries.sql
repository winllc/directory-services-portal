-- Outbound webhooks (integration API): the outbox and delivery log. Each row is one event for one
-- webhook; replicas claim due rows with FOR UPDATE SKIP LOCKED. The webhooks themselves (with their
-- signing secrets) and the API tokens (as SHA-256) are dsp_document documents.
CREATE TABLE dsp_webhook_delivery (
  id CHAR(36) NOT NULL,
  webhook_id CHAR(36) NOT NULL,
  event_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  payload TEXT NOT NULL,
  status VARCHAR(16) NOT NULL,
  attempts INT NOT NULL,
  next_attempt_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  last_attempt_at TIMESTAMPTZ,
  last_status INT,
  last_error VARCHAR(1000),
  delivered_at TIMESTAMPTZ,
  CONSTRAINT dsp_webhook_delivery_pk PRIMARY KEY (id)
);
CREATE INDEX dsp_webhook_delivery_due ON dsp_webhook_delivery (status, next_attempt_at);
CREATE INDEX dsp_webhook_delivery_hook ON dsp_webhook_delivery (webhook_id, created_at);
