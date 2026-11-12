ALTER TABLE admin_cfg.delivery_zone_versions ADD COLUMN retired_at timestamptz;

ALTER TABLE admin_cfg.dispatch_rule_versions
  ADD COLUMN activated_at timestamptz,
  ADD COLUMN retired_at timestamptz;

ALTER TABLE admin_cfg.pod_policy_versions
  ADD COLUMN activated_at timestamptz,
  ADD COLUMN retired_at timestamptz;

UPDATE admin_cfg.dispatch_rule_versions SET activated_at = created_at WHERE status = 'ACTIVE' AND activated_at IS NULL;
UPDATE admin_cfg.pod_policy_versions SET activated_at = created_at WHERE status = 'ACTIVE' AND activated_at IS NULL;

CREATE FUNCTION admin_cfg.forbid_published_version_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.status IN ('ACTIVE', 'RETIRED') THEN
      RAISE EXCEPTION 'A published configuration version cannot be deleted';
    END IF;
    RETURN OLD;
  END IF;
  IF OLD.status IN ('ACTIVE', 'RETIRED') THEN
    IF (to_jsonb(NEW) - 'status' - 'retired_at') IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'retired_at') THEN
      RAISE EXCEPTION 'A published configuration version cannot be changed; create a new version instead';
    END IF;
    IF NOT (OLD.status = NEW.status OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')) THEN
      RAISE EXCEPTION 'A published configuration version can only move from ACTIVE to RETIRED';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_zone_versions_immutable
  BEFORE UPDATE OR DELETE ON admin_cfg.delivery_zone_versions
  FOR EACH ROW EXECUTE FUNCTION admin_cfg.forbid_published_version_change();

CREATE TRIGGER trg_rule_versions_immutable
  BEFORE UPDATE OR DELETE ON admin_cfg.dispatch_rule_versions
  FOR EACH ROW EXECUTE FUNCTION admin_cfg.forbid_published_version_change();

CREATE TRIGGER trg_pod_policy_versions_immutable
  BEFORE UPDATE OR DELETE ON admin_cfg.pod_policy_versions
  FOR EACH ROW EXECUTE FUNCTION admin_cfg.forbid_published_version_change();
