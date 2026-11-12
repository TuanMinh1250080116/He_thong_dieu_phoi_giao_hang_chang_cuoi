package vn.edu.finalism.logistics.modules.driverfleet.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import vn.edu.finalism.logistics.modules.driverfleet.application.port.DriverRepository;
import vn.edu.finalism.logistics.modules.driverfleet.domain.Availability;
import vn.edu.finalism.logistics.modules.driverfleet.domain.Driver;

@Repository
class JdbcDriverRepository implements DriverRepository {

	private static final String SELECT = """
			SELECT id, user_id, code, full_name, is_active, availability, vehicle_id, version
			FROM fleet.drivers
			""";

	private final JdbcClient jdbc;

	JdbcDriverRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<Driver> findByUserId(UUID userId) {
		return jdbc.sql(SELECT + " WHERE user_id = :id").param("id", userId)
				.query((rs, i) -> map(rs)).optional();
	}

	@Override
	public Optional<Driver> findById(UUID driverId) {
		return jdbc.sql(SELECT + " WHERE id = :id").param("id", driverId)
				.query((rs, i) -> map(rs)).optional();
	}

	@Override
	public Optional<Driver> findByIdForUpdate(UUID driverId) {
		return jdbc.sql(SELECT + " WHERE id = :id FOR UPDATE").param("id", driverId)
				.query((rs, i) -> map(rs)).optional();
	}

	@Override
	public void saveAvailability(Driver driver, Instant now) {
		jdbc.sql("""
				UPDATE fleet.drivers SET availability = :availability, updated_at = :now, version = version + 1
				WHERE id = :id
				""")
				.param("availability", driver.availability().name())
				.param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
				.param("id", driver.id())
				.update();
	}

	private static Driver map(ResultSet rs) throws SQLException {
		return new Driver(
				rs.getObject("id", UUID.class),
				rs.getObject("user_id", UUID.class),
				rs.getString("code"),
				rs.getString("full_name"),
				rs.getBoolean("is_active"),
				Availability.valueOf(rs.getString("availability")),
				rs.getObject("vehicle_id", UUID.class),
				rs.getLong("version"));
	}
}
