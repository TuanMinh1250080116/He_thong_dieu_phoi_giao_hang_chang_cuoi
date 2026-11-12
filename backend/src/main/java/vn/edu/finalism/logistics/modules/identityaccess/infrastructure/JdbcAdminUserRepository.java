package vn.edu.finalism.logistics.modules.identityaccess.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vn.edu.finalism.logistics.modules.identityaccess.application.port.AdminUserRepository;
import vn.edu.finalism.logistics.shared.PageCursor;

@Repository
class JdbcAdminUserRepository implements AdminUserRepository {

	private static final String SELECT = """
			SELECT u.id, u.email, u.username, u.display_name, u.phone, u.status, u.version, u.created_at,
			       COALESCE((SELECT array_agg(r.code ORDER BY r.code) FROM iam.user_roles ur
			                 JOIN iam.roles r ON r.id = ur.role_id WHERE ur.user_id = u.id), '{}') AS roles,
			       COALESCE((SELECT array_agg(us.zone_id ORDER BY us.zone_id) FROM iam.user_scopes us
			                 WHERE us.user_id = u.id), '{}') AS zones
			FROM iam.users u
			""";

	private final JdbcClient jdbc;

	JdbcAdminUserRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<UserView> list(String status, String role, String query, PageCursor cursor, int limit) {
		StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
		Map<String, Object> params = new LinkedHashMap<>();
		if (status != null) {
			sql.append(" AND u.status = :status");
			params.put("status", status);
		}
		if (role != null) {
			sql.append(" AND EXISTS (SELECT 1 FROM iam.user_roles ur JOIN iam.roles r ON r.id = ur.role_id WHERE ur.user_id = u.id AND r.code = :role)");
			params.put("role", role);
		}
		if (query != null) {
			sql.append(" AND (u.email ILIKE :q ESCAPE '\\' OR u.display_name ILIKE :q ESCAPE '\\' OR u.username ILIKE :q ESCAPE '\\')");
			params.put("q", "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
		}
		if (cursor != null) {
			sql.append(" AND (date_trunc('milliseconds', u.created_at), u.id) < (:cursorTime, CAST(:cursorId AS uuid))");
			params.put("cursorTime", utc(cursor.sortKey()));
			params.put("cursorId", cursor.id().toString());
		}
		sql.append(" ORDER BY date_trunc('milliseconds', u.created_at) DESC, u.id DESC LIMIT :limit");
		params.put("limit", limit);
		JdbcClient.StatementSpec spec = jdbc.sql(sql.toString());
		for (Map.Entry<String, Object> param : params.entrySet()) {
			spec = spec.param(param.getKey(), param.getValue());
		}
		return spec.query((rs, i) -> map(rs)).list();
	}

	@Override
	public Optional<UserView> findView(UUID id) {
		return jdbc.sql(SELECT + " WHERE u.id = :id").param("id", id).query((rs, i) -> map(rs)).optional();
	}

	@Override
	public Optional<UserView> findForUpdate(UUID id) {
		jdbc.sql("SELECT id FROM iam.users WHERE id = :id FOR UPDATE").param("id", id).query(UUID.class).optional();
		return findView(id);
	}

	@Override
	public boolean emailExists(String email) {
		return jdbc.sql("SELECT count(*) FROM iam.users WHERE email = :email").param("email", email)
				.query(Integer.class).single() > 0;
	}

	@Override
	public void insert(UUID id, String email, String username, String displayName, String phone, String passwordHash, Instant now) {
		jdbc.sql("""
				INSERT INTO iam.users (id, email, username, display_name, phone, password_hash, status, created_at, updated_at)
				VALUES (:id, :email, :username, :name, :phone, :hash, 'ACTIVE', :now, :now)
				""")
				.param("id", id).param("email", email).param("username", username).param("name", displayName).param("phone", phone).param("hash", passwordHash)
				.param("now", utc(now)).update();
	}

	@Override
	public boolean usernameTaken(String username, UUID exceptUserId) {
		return jdbc.sql("SELECT count(*) FROM iam.users WHERE username = :username AND id <> :except")
				.param("username", username).param("except", exceptUserId == null ? new UUID(0, 0) : exceptUserId)
				.query(Integer.class).single() > 0;
	}

	@Override
	public boolean updateProfile(UUID id, String displayName, String username, String phone, long expectedVersion, Instant now) {
		return jdbc.sql("""
				UPDATE iam.users SET display_name = :name, username = COALESCE(:username, username), phone = :phone, updated_at = :now, version = version + 1
				WHERE id = :id AND version = :version
				""")
				.param("name", displayName).param("username", username).param("phone", phone).param("now", utc(now)).param("id", id).param("version", expectedVersion)
				.update() == 1;
	}

	@Override
	public boolean setStatus(UUID id, String status, long expectedVersion, Instant now) {
		return jdbc.sql("""
				UPDATE iam.users
				SET status = :status, failed_login_count = 0, locked_until = NULL, updated_at = :now,
				    version = version + 1
				WHERE id = :id AND version = :version
				""")
				.param("status", status).param("now", utc(now)).param("id", id).param("version", expectedVersion)
				.update() == 1;
	}

	@Override
	public boolean touchVersion(UUID id, long expectedVersion, Instant now) {
		return jdbc.sql("UPDATE iam.users SET updated_at = :now, version = version + 1 WHERE id = :id AND version = :version")
				.param("now", utc(now)).param("id", id).param("version", expectedVersion).update() == 1;
	}

	@Override
	public Map<String, UUID> roleIdsByCode(Collection<String> codes) {
		if (codes.isEmpty()) {
			return Map.of();
		}
		Map<String, UUID> result = new HashMap<>();
		jdbc.sql("SELECT code, id FROM iam.roles WHERE code IN (:codes)").param("codes", codes)
				.query((rs, i) -> result.put(rs.getString("code"), rs.getObject("id", UUID.class))).list();
		return result;
	}

	@Override
	public Set<UUID> existingZoneIds(Collection<UUID> zoneIds) {
		if (zoneIds.isEmpty()) {
			return Set.of();
		}
		return Set.copyOf(jdbc.sql("SELECT id FROM admin_cfg.delivery_zones WHERE id IN (:ids)").param("ids", zoneIds)
				.query((rs, i) -> rs.getObject(1, UUID.class)).list());
	}

	@Override
	public void replaceRoles(UUID userId, Collection<UUID> roleIds, UUID grantedBy, Instant now) {
		jdbc.sql("DELETE FROM iam.user_roles WHERE user_id = :id").param("id", userId).update();
		for (UUID roleId : roleIds) {
			jdbc.sql("INSERT INTO iam.user_roles (user_id, role_id, granted_by, granted_at) VALUES (:user, :role, :by, :now)")
					.param("user", userId).param("role", roleId).param("by", grantedBy).param("now", utc(now)).update();
		}
	}

	@Override
	public void replaceScopes(UUID userId, Collection<UUID> zoneIds, UUID grantedBy, Instant now) {
		jdbc.sql("DELETE FROM iam.user_scopes WHERE user_id = :id").param("id", userId).update();
		for (UUID zoneId : zoneIds) {
			jdbc.sql("INSERT INTO iam.user_scopes (user_id, zone_id, granted_by, granted_at) VALUES (:user, :zone, :by, :now)")
					.param("user", userId).param("zone", zoneId).param("by", grantedBy).param("now", utc(now)).update();
		}
	}

	@Override
	public int countOtherActiveAdmins(UUID excludedUserId) {
		return jdbc.sql("""
				SELECT count(DISTINCT u.id)
				FROM iam.users u
				JOIN iam.user_roles ur ON ur.user_id = u.id
				JOIN iam.roles r ON r.id = ur.role_id AND r.code = 'SYSTEM_ADMIN'
				WHERE u.status = 'ACTIVE' AND u.id <> :id
				""")
				.param("id", excludedUserId).query(Integer.class).single();
	}

	@Override
	public List<String> allPermissionCodes() {
		return jdbc.sql("SELECT code FROM iam.permissions ORDER BY code").query(String.class).list();
	}

	@Override
	public boolean replaceRolePermissions(String roleCode, java.util.Collection<String> permissionCodes) {
		java.util.UUID roleId = jdbc.sql("SELECT id FROM iam.roles WHERE code = :code").param("code", roleCode)
				.query(java.util.UUID.class).optional().orElse(null);
		if (roleId == null) {
			return false;
		}
		jdbc.sql("DELETE FROM iam.role_permissions WHERE role_id = :id").param("id", roleId).update();
		for (String code : permissionCodes) {
			jdbc.sql("INSERT INTO iam.role_permissions (role_id, permission_id) SELECT :id, p.id FROM iam.permissions p WHERE p.code = :code")
					.param("id", roleId).param("code", code).update();
		}
		return true;
	}

	@Override
	public List<RoleView> roles() {
		return jdbc.sql("""
				SELECT r.code,
				       COALESCE(array_agg(p.code ORDER BY p.code) FILTER (WHERE p.code IS NOT NULL), '{}') AS permissions
				FROM iam.roles r
				LEFT JOIN iam.role_permissions rp ON rp.role_id = r.id
				LEFT JOIN iam.permissions p ON p.id = rp.permission_id
				GROUP BY r.code ORDER BY r.code
				""")
				.query((rs, i) -> new RoleView(rs.getString("code"),
						Arrays.asList((String[]) rs.getArray("permissions").getArray())))
				.list();
	}

	private static UserView map(ResultSet rs) throws SQLException {
		return new UserView(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("username"), rs.getString("display_name"),
				rs.getString("phone"), rs.getString("status"), rs.getLong("version"),
				rs.getObject("created_at", OffsetDateTime.class).toInstant(),
				Arrays.asList((String[]) rs.getArray("roles").getArray()),
				Arrays.asList((UUID[]) rs.getArray("zones").getArray()));
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
