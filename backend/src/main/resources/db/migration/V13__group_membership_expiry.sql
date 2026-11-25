-- Group access: the end dates of the memberships the portal granted for a limited time. A job
-- removes the member when the date has passed. One row per (definition, group, member): the id is
-- the SHA-256 of the definition id and the normalized group DN and member value (DNs can be longer
-- than an index row allows); group_key and member_key find a group's and a member's rows the same
-- way.
CREATE TABLE dsp_group_membership_expiry (
  id CHAR(64) NOT NULL,
  definition_id VARCHAR(200) NOT NULL,
  group_key CHAR(64) NOT NULL,
  member_key CHAR(64) NOT NULL,
  group_dn TEXT NOT NULL,
  group_name TEXT,
  member TEXT NOT NULL,
  expires_on DATE NOT NULL,
  granted_by TEXT,
  granted_at TIMESTAMPTZ NOT NULL,
  last_attempt TIMESTAMPTZ,
  CONSTRAINT dsp_group_membership_expiry_pk PRIMARY KEY (id)
);
CREATE INDEX dsp_group_membership_expiry_group ON dsp_group_membership_expiry (group_key);
CREATE INDEX dsp_group_membership_expiry_member ON dsp_group_membership_expiry (member_key);
