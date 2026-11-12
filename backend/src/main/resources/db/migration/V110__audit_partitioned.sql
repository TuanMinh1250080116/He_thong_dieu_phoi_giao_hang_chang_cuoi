
CREATE TABLE audit.audit_records (
  id              uuid NOT NULL,
  occurred_at     timestamptz NOT NULL,
  actor_id        uuid,
  actor_type      text NOT NULL CHECK (actor_type IN ('USER','SYSTEM')),
  action          text NOT NULL,
  entity_type     text NOT NULL,
  entity_id       uuid NOT NULL,
  zone_id         uuid,
  before_state    jsonb,
  after_state     jsonb,
  reason          text,
  correlation_id  text NOT NULL,
  command_id      uuid,
  PRIMARY KEY (id, occurred_at),
  CONSTRAINT ck_audit_actor CHECK (actor_type = 'SYSTEM' OR actor_id IS NOT NULL)
) PARTITION BY RANGE (occurred_at);

CREATE INDEX ix_audit_entity      ON audit.audit_records (entity_type, entity_id, occurred_at DESC);
CREATE INDEX ix_audit_actor       ON audit.audit_records (actor_id, occurred_at DESC);
CREATE INDEX ix_audit_action      ON audit.audit_records (action, occurred_at DESC);
CREATE INDEX ix_audit_correlation ON audit.audit_records (correlation_id);
CREATE INDEX ix_audit_zone_time   ON audit.audit_records (zone_id, occurred_at DESC);

CREATE TABLE audit.audit_records_2026_10 PARTITION OF audit.audit_records
  FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2026-11-01 00:00:00+00');
CREATE TABLE audit.audit_records_2026_11 PARTITION OF audit.audit_records
  FOR VALUES FROM ('2026-11-01 00:00:00+00') TO ('2026-12-01 00:00:00+00');
CREATE TABLE audit.audit_records_2026_12 PARTITION OF audit.audit_records
  FOR VALUES FROM ('2026-12-01 00:00:00+00') TO ('2027-01-01 00:00:00+00');
CREATE TABLE audit.audit_records_2027_01 PARTITION OF audit.audit_records
  FOR VALUES FROM ('2027-01-01 00:00:00+00') TO ('2027-02-01 00:00:00+00');

CREATE TABLE audit.audit_records_default PARTITION OF audit.audit_records DEFAULT;
