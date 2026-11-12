package vn.edu.finalism.logistics;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

final class TestWorld {

	static final String PASSWORD = "Correct-horse-1";

	record TestUser(UUID id, String email) {
	}

	final JdbcClient jdbc;
	final PasswordEncoder encoder;
	final UUID zoneId = UUID.randomUUID();
	final double lat0 = 1 + ThreadLocalRandom.current().nextInt(60) + ThreadLocalRandom.current().nextDouble();
	final double lng0 = 1 + ThreadLocalRandom.current().nextInt(160) + ThreadLocalRandom.current().nextDouble();

	TestUser customer;
	TestUser otherCustomer;
	TestUser dispatcher;
	TestUser ops;
	TestUser admin;
	TestUser driverUser;
	UUID driverId;
	UUID vehicleId;

	private TestWorld(JdbcClient jdbc, PasswordEncoder encoder) {
		this.jdbc = jdbc;
		this.encoder = encoder;
	}

	static TestWorld create(JdbcClient jdbc, PasswordEncoder encoder, boolean freshDriverLocation) {
		TestWorld world = new TestWorld(jdbc, encoder);
		world.build(freshDriverLocation);
		return world;
	}

	double pickupLat() {
		return lat0 + 0.03;
	}

	double pickupLng() {
		return lng0 + 0.03;
	}

	double dropoffLat() {
		return lat0 + 0.05;
	}

	double dropoffLng() {
		return lng0 + 0.05;
	}

	private void build(boolean freshDriverLocation) {
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		jdbc.sql("INSERT INTO admin_cfg.delivery_zones (id, code, name, created_at, updated_at) VALUES (:id, :code, 'Test zone', :now, :now)")
				.param("id", zoneId).param("code", "Z-" + zoneId.toString().substring(0, 8)).param("now", now).update();
		jdbc.sql("""
				INSERT INTO admin_cfg.delivery_zone_versions
				  (id, zone_id, version_no, boundary, status, effective_from, created_by, created_at, activated_at)
				VALUES (:id, :zone, 1,
				        ST_Multi(ST_MakeEnvelope(:lng0, :lat0, :lng1, :lat1, 4326))::geography,
				        'ACTIVE', :now, :by, :now, :now)
				""")
				.param("id", UUID.randomUUID()).param("zone", zoneId).param("lng0", lng0).param("lat0", lat0)
				.param("lng1", lng0 + 0.1).param("lat1", lat0 + 0.1).param("now", now).param("by", UUID.randomUUID())
				.update();

		customer = user("CUSTOMER", null);
		otherCustomer = user("CUSTOMER", null);
		dispatcher = user("DISPATCHER", zoneId);
		ops = user("OPS_MANAGER", zoneId);
		admin = user("SYSTEM_ADMIN", null);
		driverUser = user("DRIVER", null);

		vehicleId = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO fleet.vehicles (id, code, vehicle_type, capacity_weight_g, capacity_volume_cm3,
				                            created_at, updated_at)
				VALUES (:id, :code, 'VAN', 200000, 2000000000, :now, :now)
				""")
				.param("id", vehicleId).param("code", "VH-" + vehicleId.toString().substring(0, 8)).param("now", now)
				.update();
		driverId = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO fleet.drivers (id, user_id, code, full_name, availability, vehicle_id, created_at, updated_at)
				VALUES (:id, :user, :code, 'Test Driver', 'AVAILABLE', :vehicle, :now, :now)
				""")
				.param("id", driverId).param("user", driverUser.id())
				.param("code", "DR-" + driverId.toString().substring(0, 8)).param("vehicle", vehicleId)
				.param("now", now).update();
		jdbc.sql("INSERT INTO fleet.driver_zones (driver_id, zone_id) VALUES (:driver, :zone)")
				.param("driver", driverId).param("zone", zoneId).update();
		jdbc.sql("""
				INSERT INTO fleet.driver_locations (driver_id, location, accuracy_m, recorded_at)
				VALUES (:driver, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, 5, :recorded)
				""")
				.param("driver", driverId).param("lng", lng0 + 0.02).param("lat", lat0 + 0.02)
				.param("recorded", freshDriverLocation ? now : now.minusHours(2)).update();
	}

	record DriverRef(TestUser user, UUID driverId) {
	}

	DriverRef addDriver() {
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		TestUser driverLogin = user("DRIVER", null);
		UUID vehicle = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO fleet.vehicles (id, code, vehicle_type, capacity_weight_g, capacity_volume_cm3,
				                            created_at, updated_at)
				VALUES (:id, :code, 'VAN', 200000, 2000000000, :now, :now)
				""")
				.param("id", vehicle).param("code", "VH-" + vehicle.toString().substring(0, 8)).param("now", now)
				.update();
		UUID driver = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO fleet.drivers (id, user_id, code, full_name, availability, vehicle_id, created_at, updated_at)
				VALUES (:id, :user, :code, 'Second Driver', 'AVAILABLE', :vehicle, :now, :now)
				""")
				.param("id", driver).param("user", driverLogin.id())
				.param("code", "DR-" + driver.toString().substring(0, 8)).param("vehicle", vehicle)
				.param("now", now).update();
		jdbc.sql("INSERT INTO fleet.driver_zones (driver_id, zone_id) VALUES (:driver, :zone)")
				.param("driver", driver).param("zone", zoneId).update();
		jdbc.sql("""
				INSERT INTO fleet.driver_locations (driver_id, location, accuracy_m, recorded_at)
				VALUES (:driver, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, 5, :recorded)
				""")
				.param("driver", driver).param("lng", lng0 + 0.07).param("lat", lat0 + 0.07)
				.param("recorded", now).update();
		return new DriverRef(driverLogin, driver);
	}

	void grantRole(TestUser target, String roleCode) {
		jdbc.sql("INSERT INTO iam.user_roles (user_id, role_id, granted_at) SELECT :id, r.id, :now FROM iam.roles r WHERE r.code = :role")
				.param("id", target.id()).param("role", roleCode).param("now", OffsetDateTime.now(ZoneOffset.UTC))
				.update();
	}

	private TestUser user(String roleCode, UUID scopeZone) {
		UUID id = UUID.randomUUID();
		String email = roleCode.toLowerCase() + "-" + id + "@test.local";
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		jdbc.sql("""
				INSERT INTO iam.users (id, email, display_name, password_hash, status, created_at, updated_at)
				VALUES (:id, :email, :name, :hash, 'ACTIVE', :now, :now)
				""")
				.param("id", id).param("email", email).param("name", roleCode + " user")
				.param("hash", encoder.encode(PASSWORD)).param("now", now).update();
		jdbc.sql("INSERT INTO iam.user_roles (user_id, role_id, granted_at) SELECT :id, r.id, :now FROM iam.roles r WHERE r.code = :role")
				.param("id", id).param("role", roleCode).param("now", now).update();
		if (scopeZone != null) {
			jdbc.sql("INSERT INTO iam.user_scopes (user_id, zone_id, granted_at) VALUES (:id, :zone, :now)")
					.param("id", id).param("zone", scopeZone).param("now", now).update();
		}
		return new TestUser(id, email);
	}
}
