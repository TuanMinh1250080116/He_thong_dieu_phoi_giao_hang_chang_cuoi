CREATE INDEX IF NOT EXISTS ix_assignments_driver_recent
  ON dispatch.driver_assignments (driver_id, decided_at DESC) WHERE status IN ('PENDING','ACCEPTED');
