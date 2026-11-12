package vn.edu.finalism.logistics.modules.identityaccess.infrastructure;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import vn.edu.finalism.logistics.shared.ClockPort;

@Component
@Profile("dev")
@org.springframework.core.annotation.Order(1)
class DevAccountSeeder implements ApplicationRunner {

	static final String PASSWORD = "Passw0rd!dev";

	private static final Logger log = LoggerFactory.getLogger(DevAccountSeeder.class);

	private final JdbcClient jdbc;
	private final PasswordEncoder encoder;
	private final ClockPort clock;

	DevAccountSeeder(JdbcClient jdbc, PasswordEncoder encoder, ClockPort clock) {
		this.jdbc = jdbc;
		this.encoder = encoder;
		this.clock = clock;
	}

	@Override
	public void run(ApplicationArguments args) {
		create("customer@dev.local", "Dev Customer", "CUSTOMER");
		create("dispatcher@dev.local", "Dev Dispatcher", "DISPATCHER");
		create("driver@dev.local", "Dev Driver", "DRIVER");
		create("driver2@dev.local", "Dev Driver Two", "DRIVER");
		grant("dispatcher@dev.local", "DISPATCH_OVERRIDER");
		create("ops@dev.local", "Dev Operations Manager", "OPS_MANAGER");
		create("admin@dev.local", "Dev System Admin", "SYSTEM_ADMIN");
		jdbc.sql("UPDATE iam.users SET username = split_part(email::text, '@', 1) WHERE email::text LIKE '%@dev.local' AND username IS NULL")
				.update();
		log.warn("Dev accounts are available (password '{}'). Do not use the dev profile in production.", PASSWORD);
	}

	private void grant(String email, String roleCode) {
		jdbc.sql("""
				INSERT INTO iam.user_roles (user_id, role_id, granted_at)
				SELECT u.id, r.id, :now FROM iam.users u, iam.roles r
				WHERE u.email = :email AND r.code = :role
				ON CONFLICT DO NOTHING
				""")
				.param("email", email).param("role", roleCode)
				.param("now", OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)).update();
	}

	private void create(String email, String displayName, String roleCode) {
		boolean exists = jdbc.sql("SELECT count(*) FROM iam.users WHERE email = :email")
				.param("email", email).query(Integer.class).single() > 0;
		if (exists) {
			return;
		}
		OffsetDateTime now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC);
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO iam.users (id, email, display_name, password_hash, status, created_at, updated_at)
				VALUES (:id, :email, :name, :hash, 'ACTIVE', :now, :now)
				""")
				.param("id", id).param("email", email).param("name", displayName)
				.param("hash", encoder.encode(PASSWORD)).param("now", now)
				.update();
		jdbc.sql("""
				INSERT INTO iam.user_roles (user_id, role_id, granted_at)
				SELECT :id, r.id, :now FROM iam.roles r WHERE r.code = :role
				""")
				.param("id", id).param("role", roleCode).param("now", now)
				.update();
	}
}
