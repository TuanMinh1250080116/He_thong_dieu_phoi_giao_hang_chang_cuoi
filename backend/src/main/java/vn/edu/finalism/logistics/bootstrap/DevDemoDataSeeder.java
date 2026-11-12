package vn.edu.finalism.logistics.bootstrap;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
@Order(2)
class DevDemoDataSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DevDemoDataSeeder.class);
	private static final String ZONE_CODE = "DEV-HCM";

	private final JdbcClient jdbc;

	DevDemoDataSeeder(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (count("SELECT count(*) FROM admin_cfg.delivery_zones WHERE code = '" + ZONE_CODE + "'") > 0) {
			ensureSecondDriver();
			grantOpsScope();
			refreshDriverLocation();
			return;
		}
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		UUID zoneId = UUID.randomUUID();
		jdbc.sql("INSERT INTO admin_cfg.delivery_zones (id, code, name, created_at, updated_at) VALUES (:id, :code, 'Ho Chi Minh City (dev)', :now, :now)")
				.param("id", zoneId).param("code", ZONE_CODE).param("now", now).update();
		jdbc.sql("""
				INSERT INTO admin_cfg.delivery_zone_versions
				  (id, zone_id, version_no, boundary, status, effective_from, created_by, created_at, activated_at)
				VALUES (:id, :zone, 1, ST_Multi(ST_MakeEnvelope(106.60, 10.70, 106.80, 10.85, 4326))::geography,
				        'ACTIVE', :now, :by, :now, :now)
				""")
				.param("id", UUID.randomUUID()).param("zone", zoneId).param("now", now).param("by", UUID.randomUUID())
				.update();
		jdbc.sql("""
				INSERT INTO iam.user_scopes (user_id, zone_id, granted_at)
				SELECT id, :zone, :now FROM iam.users WHERE email = 'dispatcher@dev.local'
				""")
				.param("zone", zoneId).param("now", now).update();

		UUID vehicleId = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO fleet.vehicles (id, code, vehicle_type, capacity_weight_g, capacity_volume_cm3, created_at, updated_at)
				VALUES (:id, 'DEV-VAN-01', 'VAN', 500000, 5000000000, :now, :now)
				""")
				.param("id", vehicleId).param("now", now).update();
		UUID driverId = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO fleet.drivers (id, user_id, code, full_name, availability, vehicle_id, created_at, updated_at)
				SELECT :id, id, 'DEV-DRV-01', 'Dev Driver', 'AVAILABLE', :vehicle, :now, :now
				FROM iam.users WHERE email = 'driver@dev.local'
				""")
				.param("id", driverId).param("vehicle", vehicleId).param("now", now).update();
		jdbc.sql("INSERT INTO fleet.driver_zones (driver_id, zone_id) VALUES (:driver, :zone)")
				.param("driver", driverId).param("zone", zoneId).update();
		jdbc.sql("""
				INSERT INTO fleet.driver_locations (driver_id, location, accuracy_m, recorded_at)
				VALUES (:driver, ST_SetSRID(ST_MakePoint(106.70, 10.77), 4326)::geography, 5, :now)
				""")
				.param("driver", driverId).param("now", now).update();
		ensureSecondDriver();
		grantOpsScope();
		log.warn("Dev demo data created: zone {}, vans and drivers (driver@dev.local, driver2@dev.local) near 10.77, 106.70.", ZONE_CODE);
	}

	private void grantOpsScope() {
		jdbc.sql("""
				INSERT INTO iam.user_scopes (user_id, zone_id, granted_at)
				SELECT u.id, z.id, :now FROM iam.users u, admin_cfg.delivery_zones z
				WHERE u.email = 'ops@dev.local' AND z.code = :zone
				ON CONFLICT DO NOTHING
				""")
				.param("zone", ZONE_CODE).param("now", OffsetDateTime.now(ZoneOffset.UTC)).update();
	}

	private void ensureSecondDriver() {
		if (count("SELECT count(*) FROM fleet.drivers WHERE code = 'DEV-DRV-02'") > 0) {
			return;
		}
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		UUID vehicleId = UUID.randomUUID();
		UUID driverId = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO fleet.vehicles (id, code, vehicle_type, capacity_weight_g, capacity_volume_cm3, created_at, updated_at)
				VALUES (:id, 'DEV-VAN-02', 'VAN', 500000, 5000000000, :now, :now)
				""")
				.param("id", vehicleId).param("now", now).update();
		int created = jdbc.sql("""
				INSERT INTO fleet.drivers (id, user_id, code, full_name, availability, vehicle_id, created_at, updated_at)
				SELECT :id, id, 'DEV-DRV-02', 'Dev Driver Two', 'AVAILABLE', :vehicle, :now, :now
				FROM iam.users WHERE email = 'driver2@dev.local'
				""")
				.param("id", driverId).param("vehicle", vehicleId).param("now", now).update();
		if (created == 0) {
			return;
		}
		jdbc.sql("""
				INSERT INTO fleet.driver_zones (driver_id, zone_id)
				SELECT :driver, id FROM admin_cfg.delivery_zones WHERE code = :zone
				""")
				.param("driver", driverId).param("zone", ZONE_CODE).update();
		jdbc.sql("""
				INSERT INTO fleet.driver_locations (driver_id, location, accuracy_m, recorded_at)
				VALUES (:driver, ST_SetSRID(ST_MakePoint(106.72, 10.79), 4326)::geography, 5, :now)
				""")
				.param("driver", driverId).param("now", now).update();
	}

	@Scheduled(fixedRate = 300_000, initialDelay = 300_000)
	void refreshDriverLocation() {
		jdbc.sql("""
				UPDATE fleet.driver_locations SET recorded_at = :now
				WHERE driver_id IN (SELECT id FROM fleet.drivers WHERE code IN ('DEV-DRV-01', 'DEV-DRV-02'))
				""")
				.param("now", OffsetDateTime.now(ZoneOffset.UTC)).update();
	}

	private long count(String sql) {
		return jdbc.sql(sql).query(Long.class).single();
	}
}
