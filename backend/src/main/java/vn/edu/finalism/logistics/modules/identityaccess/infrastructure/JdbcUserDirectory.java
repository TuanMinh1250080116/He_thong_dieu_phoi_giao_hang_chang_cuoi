package vn.edu.finalism.logistics.modules.identityaccess.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vn.edu.finalism.logistics.modules.identityaccess.application.UserDirectoryPort;

@Repository
class JdbcUserDirectory implements UserDirectoryPort {

	private final JdbcClient jdbc;

	JdbcUserDirectory(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<UUID> userIdsWithPermissionInZone(String permission, UUID zoneId) {
		return jdbc.sql("""
				SELECT DISTINCT u.id
				FROM iam.users u
				JOIN iam.user_scopes s ON s.user_id = u.id AND s.zone_id = :zone
				JOIN iam.user_roles ur ON ur.user_id = u.id
				JOIN iam.role_permissions rp ON rp.role_id = ur.role_id
				JOIN iam.permissions p ON p.id = rp.permission_id AND p.code = :permission
				WHERE u.status = 'ACTIVE'
				""")
				.param("zone", zoneId).param("permission", permission)
				.query((rs, i) -> rs.getObject(1, UUID.class)).list();
	}

	@Override
	public boolean userHasPermission(UUID userId, String permission) {
		return jdbc.sql("""
				SELECT count(*)
				FROM iam.users u
				JOIN iam.user_roles ur ON ur.user_id = u.id
				JOIN iam.role_permissions rp ON rp.role_id = ur.role_id
				JOIN iam.permissions p ON p.id = rp.permission_id AND p.code = :permission
				WHERE u.id = :id AND u.status = 'ACTIVE'
				""")
				.param("id", userId).param("permission", permission).query(Integer.class).single() > 0;
	}

	@Override
	public void grantScope(UUID userId, UUID zoneId, UUID grantedBy) {
		jdbc.sql("""
				INSERT INTO iam.user_scopes (user_id, zone_id, granted_by, granted_at)
				VALUES (:user, :zone, :by, now())
				ON CONFLICT DO NOTHING
				""")
				.param("user", userId).param("zone", zoneId).param("by", grantedBy).update();
	}

	@Override
	public Optional<String> displayName(UUID userId) {
		return jdbc.sql("SELECT display_name FROM iam.users WHERE id = :id").param("id", userId)
				.query(String.class).optional();
	}
}
