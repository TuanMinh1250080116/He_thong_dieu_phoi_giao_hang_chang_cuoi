package vn.edu.finalism.logistics.modules.identityaccess.infrastructure;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vn.edu.finalism.logistics.modules.identityaccess.application.port.UserAccountRepository;
import vn.edu.finalism.logistics.modules.identityaccess.domain.AccountStatus;
import vn.edu.finalism.logistics.modules.identityaccess.domain.UserAccount;

@Repository
class JdbcUserAccountRepository implements UserAccountRepository {

	private static final String SELECT_USER = """
			SELECT id, email, display_name, password_hash, status, locked_until, version
			FROM iam.users
			""";

	private final JdbcClient jdbc;

	JdbcUserAccountRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<UserAccount> findByLoginIdentifier(String identifier) {
		return jdbc.sql(SELECT_USER + " WHERE email = :identifier OR username = :identifier")
				.param("identifier", identifier)
				.query((rs, i) -> mapUser(rs))
				.optional();
	}

	@Override
	public Optional<UserAccount> findById(UUID id) {
		return jdbc.sql(SELECT_USER + " WHERE id = :id")
				.param("id", id)
				.query((rs, i) -> mapUser(rs))
				.optional();
	}

	@Override
	public Authorities loadAuthorities(UUID userId) {
		Set<String> roles = new HashSet<>(jdbc.sql("""
				SELECT r.code FROM iam.user_roles ur
				JOIN iam.roles r ON r.id = ur.role_id
				WHERE ur.user_id = :id
				""").param("id", userId).query(String.class).list());

		Set<String> permissions = new HashSet<>(jdbc.sql("""
				SELECT DISTINCT p.code FROM iam.user_roles ur
				JOIN iam.role_permissions rp ON rp.role_id = ur.role_id
				JOIN iam.permissions p ON p.id = rp.permission_id
				WHERE ur.user_id = :id
				""").param("id", userId).query(String.class).list());

		Set<UUID> zones = new HashSet<>(jdbc.sql("SELECT zone_id FROM iam.user_scopes WHERE user_id = :id")
				.param("id", userId)
				.query((rs, i) -> rs.getObject(1, UUID.class))
				.list());

		return new Authorities(roles, permissions, zones);
	}

	@Override
	public void registerFailedLogin(UUID userId, Instant now, int maxAttempts, Duration lockDuration) {
		jdbc.sql("""
				UPDATE iam.users SET
				  failed_login_count = CASE WHEN failed_login_count + 1 >= :max THEN 0 ELSE failed_login_count + 1 END,
				  locked_until = CASE WHEN failed_login_count + 1 >= :max THEN :lockedUntil ELSE locked_until END,
				  updated_at = :now,
				  version = version + 1
				WHERE id = :id
				""")
				.param("max", maxAttempts)
				.param("lockedUntil", utc(now.plus(lockDuration)))
				.param("now", utc(now))
				.param("id", userId)
				.update();
	}

	@Override
	public void registerSuccessfulLogin(UUID userId, Instant now) {
		jdbc.sql("""
				UPDATE iam.users SET failed_login_count = 0, locked_until = NULL,
				  updated_at = :now, version = version + 1
				WHERE id = :id
				""")
				.param("now", utc(now))
				.param("id", userId)
				.update();
	}

	private static UserAccount mapUser(java.sql.ResultSet rs) throws java.sql.SQLException {
		OffsetDateTime lockedUntil = rs.getObject("locked_until", OffsetDateTime.class);
		return new UserAccount(
				rs.getObject("id", UUID.class),
				rs.getString("email"),
				rs.getString("display_name"),
				rs.getString("password_hash"),
				AccountStatus.valueOf(rs.getString("status")),
				lockedUntil == null ? null : lockedUntil.toInstant(),
				rs.getLong("version"));
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
