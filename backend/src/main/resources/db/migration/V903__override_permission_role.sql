INSERT INTO iam.roles (id, code) VALUES (gen_random_uuid(), 'DISPATCH_OVERRIDER');

INSERT INTO iam.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM iam.roles r
JOIN iam.permissions p ON p.code = 'dispatch.recommendation.override'
WHERE r.code = 'DISPATCH_OVERRIDER';
