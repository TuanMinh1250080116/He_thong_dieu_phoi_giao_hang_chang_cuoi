INSERT INTO admin_cfg.rejection_reason_catalog (code, requires_description) VALUES
  ('VEHICLE_PROBLEM', false),
  ('SCHEDULE_CONFLICT', false),
  ('TOO_FAR_FROM_PICKUP', false),
  ('CAPACITY_CONCERN', false),
  ('PERSONAL_EMERGENCY', false),
  ('OTHER', true);

INSERT INTO admin_cfg.exception_type_catalog (code, name, default_severity, impact) VALUES
  ('RECIPIENT_ABSENT', 'Recipient absent', 'MEDIUM', 'BLOCKS_STOP'),
  ('ADDRESS_INVALID', 'Address invalid', 'MEDIUM', 'BLOCKS_STOP'),
  ('VEHICLE_BREAKDOWN', 'Vehicle breakdown', 'HIGH', 'BLOCKS_DELIVERY_COMPLETION'),
  ('PACKAGE_DAMAGED', 'Package damaged', 'HIGH', 'CUSTOMER_IMPACTING'),
  ('ACCESS_DENIED', 'Access denied', 'LOW', 'NON_BLOCKING');

INSERT INTO admin_cfg.dispatch_rule_versions
  (id, zone_id, version_no, name, status, w_distance, w_capacity_fit, w_availability, w_zone_compat,
   ttl_seconds, search_radius_m, max_location_age_s, persist_top_n, effective_from, created_by, created_at)
VALUES
  (gen_random_uuid(), NULL, 1, 'Default dispatch rule', 'ACTIVE', 0.4000, 0.2500, 0.1500, 0.2000,
   300, 20000, 600, 50, '2026-01-01T00:00:00Z', '00000000-0000-0000-0000-000000000000', '2026-01-01T00:00:00Z');

INSERT INTO admin_cfg.pod_policy_versions
  (id, zone_id, version_no, name, status, requires_gps, requires_server_ts, max_gps_accuracy_m,
   evidence_expression, recipient_name_rule, effective_from, created_by, created_at)
VALUES
  (gen_random_uuid(), NULL, 1, 'Default POD policy', 'ACTIVE', true, true, 100,
   '{"op":"ANY_OF","items":["OTP","PHOTO","SIGNATURE"]}'::jsonb, 'DIRECT_HANDOFF',
   '2026-01-01T00:00:00Z', '00000000-0000-0000-0000-000000000000', '2026-01-01T00:00:00Z');
