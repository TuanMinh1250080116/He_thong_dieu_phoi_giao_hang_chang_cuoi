package vn.edu.finalism.logistics.modules.driverfleet.application.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import vn.edu.finalism.logistics.shared.PageCursor;

public interface FleetRepository {

	List<VehicleRow> listVehicles(Boolean active, String query, PageCursor cursor, int limit);

	Optional<VehicleRow> findVehicle(UUID id);

	Optional<VehicleRow> findVehicleForUpdate(UUID id);

	boolean vehicleCodeExists(String code);

	void insertVehicle(UUID id, String code, String type, int weightG, long volumeCm3, String note, Instant now);

	boolean updateVehicle(UUID id, String type, int weightG, long volumeCm3, String note, long expectedVersion,
			Instant now);

	boolean setVehicleActive(UUID id, boolean active, long expectedVersion, Instant now);

	Optional<UUID> driverUsingVehicle(UUID vehicleId, UUID exceptDriverId);

	List<DriverRow> listDrivers(Set<UUID> scopeZoneIds, Boolean active, String query, PageCursor cursor, int limit);

	Optional<DriverRow> findDriver(UUID id);

	Optional<DriverRow> findDriverForUpdate(UUID id);

	Optional<DriverRow> findDriverByUser(UUID userId);

	boolean userHasRole(UUID userId, String roleCode);

	List<DriverCandidate> driverCandidates();

	record DriverCandidate(UUID userId, String email, String displayName) {
	}

	boolean driverExistsForUser(UUID userId);

	String nextDriverCode();

	void insertDriver(UUID id, UUID userId, String code, String fullName, String contact, UUID vehicleId, Instant now);

	boolean updateDriver(UUID id, String fullName, String contact, UUID vehicleId, long expectedVersion, Instant now);

	boolean setDriverActive(UUID id, boolean active, long expectedVersion, Instant now);

	boolean setDriverAvailability(UUID id, String availability, long expectedVersion, Instant now);

	void replaceDriverZones(UUID driverId, Collection<UUID> zoneIds);

	Set<UUID> existingZoneIds(Collection<UUID> zoneIds);

	List<UUID> openAssignmentIdsOfDriver(UUID driverId);

	int pendingAssignmentCount(UUID driverId);

	void upsertLocation(UUID driverId, double lat, double lng, Double accuracyM, Instant recordedAt);

	Optional<LocationRow> findLocation(UUID driverId);

	record VehicleRow(UUID id, String code, String vehicleType, int capacityWeightG, long capacityVolumeCm3,
			boolean active, String note, long version, Instant createdAt) {
	}

	record DriverRow(UUID id, UUID userId, String code, String fullName, String contact, boolean active,
			String availability, UUID vehicleId, String vehicleCode, String vehicleType, List<UUID> zoneIds,
			long version, Instant createdAt) {
	}

	record LocationRow(double lat, double lng, Double accuracyM, Instant recordedAt) {
	}
}
