ALTER TABLE admin_cfg.dispatch_rule_versions DROP CONSTRAINT IF EXISTS dispatch_rule_versions_status_check;
ALTER TABLE admin_cfg.dispatch_rule_versions ADD CONSTRAINT ck_rule_versions_status
  CHECK (status IN ('DRAFT','SCHEDULED','ACTIVE','RETIRED','CANCELLED'));
ALTER TABLE admin_cfg.pod_policy_versions DROP CONSTRAINT IF EXISTS pod_policy_versions_status_check;
ALTER TABLE admin_cfg.pod_policy_versions ADD CONSTRAINT ck_pod_policy_versions_status
  CHECK (status IN ('DRAFT','SCHEDULED','ACTIVE','RETIRED','CANCELLED'));

CREATE UNIQUE INDEX uq_rule_versions_scheduled
  ON admin_cfg.dispatch_rule_versions (COALESCE(zone_id, '00000000-0000-0000-0000-000000000000'))
  WHERE status = 'SCHEDULED';
CREATE UNIQUE INDEX uq_pod_policy_versions_scheduled
  ON admin_cfg.pod_policy_versions (COALESCE(zone_id, '00000000-0000-0000-0000-000000000000'))
  WHERE status = 'SCHEDULED';
