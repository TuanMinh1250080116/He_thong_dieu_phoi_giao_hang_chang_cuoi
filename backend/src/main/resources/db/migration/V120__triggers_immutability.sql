
CREATE FUNCTION platform.forbid_modification() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% on %.% is not allowed: table is append-only',
    TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER trg_audit_append_only
  BEFORE UPDATE OR DELETE ON audit.audit_records
  FOR EACH ROW EXECUTE FUNCTION platform.forbid_modification();

CREATE TRIGGER trg_audit_no_truncate
  BEFORE TRUNCATE ON audit.audit_records
  FOR EACH STATEMENT EXECUTE FUNCTION platform.forbid_modification();
