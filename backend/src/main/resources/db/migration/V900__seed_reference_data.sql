
INSERT INTO iam.roles (id, code)
SELECT gen_random_uuid(), c
FROM (VALUES ('CUSTOMER'), ('DISPATCHER'), ('DRIVER'), ('OPS_MANAGER'), ('SYSTEM_ADMIN')) AS v(c);

INSERT INTO iam.permissions (id, code)
SELECT gen_random_uuid(), c
FROM (VALUES
  ('order.create'), ('order.view.own'), ('order.view.scope'),
  ('dispatch.board.view'), ('dispatch.batch.manage'), ('dispatch.recommendation.generate'),
  ('dispatch.assign'), ('dispatch.recommendation.override'), ('dispatch.reassign'), ('dispatch.cancel'),
  ('delivery.monitor'), ('delivery.execute'),
  ('proof.submit'), ('proof.view'),
  ('exception.report'), ('exception.view'), ('exception.context.add'), ('exception.manage'),
  ('fleet.manage'), ('config.zone.manage'), ('config.rule.manage'),
  ('audit.view'), ('iam.user.manage'), ('iam.role.manage')
) AS v(c);

CREATE TEMP TABLE role_permission_map (role_code text, perm_code text) ON COMMIT DROP;
INSERT INTO role_permission_map (role_code, perm_code) VALUES
  ('CUSTOMER', 'order.create'),
  ('CUSTOMER', 'order.view.own'),
  ('CUSTOMER', 'proof.view'),

  ('DISPATCHER', 'order.view.scope'),
  ('DISPATCHER', 'dispatch.board.view'),
  ('DISPATCHER', 'dispatch.batch.manage'),
  ('DISPATCHER', 'dispatch.recommendation.generate'),
  ('DISPATCHER', 'dispatch.assign'),
  ('DISPATCHER', 'dispatch.reassign'),
  ('DISPATCHER', 'dispatch.cancel'),
  ('DISPATCHER', 'delivery.monitor'),
  ('DISPATCHER', 'proof.view'),
  ('DISPATCHER', 'exception.report'),
  ('DISPATCHER', 'exception.view'),
  ('DISPATCHER', 'exception.context.add'),

  ('DRIVER', 'delivery.execute'),
  ('DRIVER', 'proof.submit'),
  ('DRIVER', 'proof.view'),
  ('DRIVER', 'exception.report'),
  ('DRIVER', 'exception.view'),
  ('DRIVER', 'exception.context.add'),

  ('OPS_MANAGER', 'order.view.scope'),
  ('OPS_MANAGER', 'dispatch.board.view'),
  ('OPS_MANAGER', 'delivery.monitor'),
  ('OPS_MANAGER', 'proof.view'),
  ('OPS_MANAGER', 'exception.report'),
  ('OPS_MANAGER', 'exception.view'),
  ('OPS_MANAGER', 'exception.context.add'),
  ('OPS_MANAGER', 'exception.manage'),
  ('OPS_MANAGER', 'fleet.manage'),
  ('OPS_MANAGER', 'config.zone.manage'),
  ('OPS_MANAGER', 'config.rule.manage'),
  ('OPS_MANAGER', 'audit.view'),

  ('SYSTEM_ADMIN', 'iam.user.manage'),
  ('SYSTEM_ADMIN', 'iam.role.manage'),
  ('SYSTEM_ADMIN', 'audit.view');

DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM role_permission_map m
    LEFT JOIN iam.roles r ON r.code = m.role_code
    LEFT JOIN iam.permissions p ON p.code = m.perm_code
    WHERE r.id IS NULL OR p.id IS NULL
  ) THEN
    RAISE EXCEPTION 'role_permission_map references an unknown role or permission';
  END IF;
END $$;

INSERT INTO iam.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM role_permission_map m
JOIN iam.roles r ON r.code = m.role_code
JOIN iam.permissions p ON p.code = m.perm_code;
