package vn.edu.finalism.logistics.modules.driverfleet.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.audit.application.AuditAction;
import vn.edu.finalism.logistics.modules.audit.application.AuditEntry;
import vn.edu.finalism.logistics.modules.audit.application.AuditWriter;
import vn.edu.finalism.logistics.modules.driverfleet.application.port.FleetRepository;
import vn.edu.finalism.logistics.modules.driverfleet.application.port.FleetRepository.DriverRow;
import vn.edu.finalism.logistics.modules.driverfleet.application.port.FleetRepository.VehicleRow;
import vn.edu.finalism.logistics.platform.idempotency.IdempotencyClaim;
import vn.edu.finalism.logistics.platform.idempotency.IdempotencyService;
import vn.edu.finalism.logistics.platform.idempotency.RequestFingerprinter;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.ErrorCode;
import vn.edu.finalism.logistics.shared.IdempotencyKey;
import vn.edu.finalism.logistics.shared.Outcome;
import vn.edu.finalism.logistics.shared.PageCursor;
import vn.edu.finalism.logistics.shared.PageResult;

@Service
public class DriverAdminHandler {

	public record Draft(UUID userId, String fullName, String contact, UUID vehicleId, List<UUID> zoneIds) {
	}

	private final FleetRepository fleet;
	private final IdempotencyService idempotency;
	private final RequestFingerprinter fingerprinter;
	private final AuditWriter audit;
	private final ClockPort clock;

	DriverAdminHandler(FleetRepository fleet, IdempotencyService idempotency, RequestFingerprinter fingerprinter,
			AuditWriter audit, ClockPort clock) {
		this.fleet = fleet;
		this.idempotency = idempotency;
		this.fingerprinter = fingerprinter;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public PageResult<DriverRow> list(ActorContext actor, Boolean active, String query, String cursor, Integer limit) {
		actor.requirePermission("fleet.manage");
		int size = PageCursor.normalizeLimit(limit);
		List<DriverRow> rows = fleet.listDrivers(actor.operationScopeIds(), active, blankToNull(query),
				cursor == null || cursor.isBlank() ? null : PageCursor.decode(cursor), size + 1);
		boolean more = rows.size() > size;
		List<DriverRow> page = more ? rows.subList(0, size) : rows;
		String next = more ? new PageCursor(page.get(page.size() - 1).createdAt(), page.get(page.size() - 1).id())
				.encode() : null;
		return new PageResult<>(List.copyOf(page), next, clock.now());
	}

	@Transactional(readOnly = true)
	public List<FleetRepository.DriverCandidate> candidates(ActorContext actor) {
		actor.requirePermission("fleet.manage");
		return fleet.driverCandidates();
	}

	@Transactional(readOnly = true)
	public DriverRow get(ActorContext actor, UUID id) {
		actor.requirePermission("fleet.manage");
		return visible(actor, fleet.findDriver(id).orElseThrow(() -> BusinessException.notFound("Driver")));
	}

	@Transactional
	public Outcome<DriverRow> create(ActorContext actor, IdempotencyKey key, Draft draft) {
		actor.requirePermission("fleet.manage");
		if (draft.userId() == null) {
			throw BusinessException.validation("userId", "REQUIRED", "The user account of the driver is required.");
		}
		String name = requireName(draft.fullName());
		Set<UUID> zones = new LinkedHashSet<>(draft.zoneIds() == null ? List.of() : draft.zoneIds());
		byte[] fingerprint = fingerprinter.fingerprint("POST /admin/drivers", null, draft);
		IdempotencyClaim claim = idempotency.claim(actor.userId(), key, "CREATE_DRIVER", fingerprint);
		if (claim instanceof IdempotencyClaim.Replay replay) {
			return Outcome.replay(fleet.findDriver(replay.result().resultRef())
					.orElseThrow(() -> BusinessException.notFound("Driver")));
		}
		if (!fleet.userHasRole(draft.userId(), "DRIVER")) {
			throw BusinessException.validation("userId", "NOT_A_DRIVER", "The user does not have the DRIVER role.");
		}
		if (fleet.driverExistsForUser(draft.userId())) {
			throw BusinessException.validation("userId", "DUPLICATE", "This user already has a driver profile.");
		}
		requireZones(actor, zones);
		requireFreeVehicle(draft.vehicleId(), null);

		Instant now = clock.now().truncatedTo(ChronoUnit.MILLIS);
		UUID id = UUID.randomUUID();
		fleet.insertDriver(id, draft.userId(), fleet.nextDriverCode(), name, blankToNull(draft.contact()),
				draft.vehicleId(), now);
		fleet.replaceDriverZones(id, zones);
		audit.append(AuditEntry.forUser(actor, AuditAction.DRIVER_CREATED, "DRIVER", id, firstZone(zones), null,
				Map.of("fullName", name, "zoneCount", zones.size()), null, null));
		idempotency.complete(actor.userId(), key, "DRIVER", id, 0L);
		return Outcome.fresh(fleet.findDriver(id).orElseThrow());
	}

	@Transactional
	public DriverRow update(ActorContext actor, UUID id, long expectedVersion, Draft draft) {
		actor.requirePermission("fleet.manage");
		DriverRow current = lock(actor, id, expectedVersion);
		String name = requireName(draft.fullName());
		Set<UUID> zones = new LinkedHashSet<>(draft.zoneIds() == null ? current.zoneIds() : draft.zoneIds());
		requireZones(actor, zones);
		if (draft.vehicleId() != null && !draft.vehicleId().equals(current.vehicleId())) {
			requireFreeVehicle(draft.vehicleId(), id);
		}
		if (current.availability().equals("RESERVED") || current.availability().equals("BUSY")) {
			if (draft.vehicleId() == null ? current.vehicleId() != null : !draft.vehicleId().equals(current.vehicleId())) {
				throw BusinessException.stateNotAllowed("The vehicle cannot change while the driver has an open assignment.");
			}
		}
		if (!fleet.updateDriver(id, name, blankToNull(draft.contact()), draft.vehicleId(), expectedVersion, clock.now())) {
			throw BusinessException.versionConflict(expectedVersion + 1);
		}
		fleet.replaceDriverZones(id, zones);
		Map<String, Object> before = new LinkedHashMap<>();
		before.put("fullName", current.fullName());
		before.put("vehicleId", current.vehicleId() == null ? null : current.vehicleId().toString());
		before.put("zoneCount", current.zoneIds().size());
		Map<String, Object> after = new LinkedHashMap<>();
		after.put("fullName", name);
		after.put("vehicleId", draft.vehicleId() == null ? null : draft.vehicleId().toString());
		after.put("zoneCount", zones.size());
		audit.append(AuditEntry.forUser(actor, AuditAction.DRIVER_UPDATED, "DRIVER", id, firstZone(zones), before, after,
				null, null));
		return fleet.findDriver(id).orElseThrow();
	}

	@Transactional
	public DriverRow deactivate(ActorContext actor, UUID id, long expectedVersion) {
		actor.requirePermission("fleet.manage");
		DriverRow current = lock(actor, id, expectedVersion);
		if (!current.active()) {
			throw BusinessException.stateNotAllowed("The driver is already inactive.");
		}
		List<UUID> open = fleet.openAssignmentIdsOfDriver(id);
		if (!open.isEmpty() || current.availability().equals("RESERVED") || current.availability().equals("BUSY")) {
			throw new BusinessException(ErrorCode.STATE_TRANSITION_NOT_ALLOWED,
					"The driver still has an assignment or a delivery in progress.",
					Map.of("blockingAssignmentIds", open.stream().map(UUID::toString).toList()));
		}
		change(id, false, expectedVersion);
		audit.append(AuditEntry.forUser(actor, AuditAction.DRIVER_DEACTIVATED, "DRIVER", id, firstZone(current.zoneIds()),
				Map.of("active", true), Map.of("active", false), null, null));
		return fleet.findDriver(id).orElseThrow();
	}

	@Transactional
	public DriverRow activate(ActorContext actor, UUID id, long expectedVersion) {
		actor.requirePermission("fleet.manage");
		DriverRow current = lock(actor, id, expectedVersion);
		if (current.active()) {
			throw BusinessException.stateNotAllowed("The driver is already active.");
		}
		change(id, true, expectedVersion);
		audit.append(AuditEntry.forUser(actor, AuditAction.DRIVER_ACTIVATED, "DRIVER", id, firstZone(current.zoneIds()),
				Map.of("active", false), Map.of("active", true), null, null));
		return fleet.findDriver(id).orElseThrow();
	}

	private void change(UUID id, boolean active, long expectedVersion) {
		if (!fleet.setDriverActive(id, active, expectedVersion, clock.now())) {
			throw BusinessException.versionConflict(expectedVersion + 1);
		}
	}

	private DriverRow lock(ActorContext actor, UUID id, long expectedVersion) {
		DriverRow current = visible(actor, fleet.findDriverForUpdate(id)
				.orElseThrow(() -> BusinessException.notFound("Driver")));
		if (current.version() != expectedVersion) {
			throw BusinessException.versionConflict(current.version());
		}
		return current;
	}

	private DriverRow visible(ActorContext actor, DriverRow driver) {
		boolean unassigned = driver.zoneIds().isEmpty();
		boolean inScope = driver.zoneIds().stream().anyMatch(actor::hasScope);
		if (!unassigned && !inScope) {
			throw BusinessException.notFound("Driver");
		}
		return driver;
	}

	private void requireZones(ActorContext actor, Set<UUID> zones) {
		if (!fleet.existingZoneIds(zones).containsAll(zones)) {
			throw BusinessException.validation("zoneIds", "UNKNOWN", "A zone does not exist.");
		}
		for (UUID zone : zones) {
			actor.requireScope(zone);
		}
	}

	private void requireFreeVehicle(UUID vehicleId, UUID driverId) {
		if (vehicleId == null) {
			return;
		}
		VehicleRow vehicle = fleet.findVehicle(vehicleId)
				.orElseThrow(() -> BusinessException.validation("vehicleId", "UNKNOWN", "The vehicle does not exist."));
		if (!vehicle.active()) {
			throw BusinessException.validation("vehicleId", "INACTIVE", "The vehicle is inactive.");
		}
		if (fleet.driverUsingVehicle(vehicleId, driverId).isPresent()) {
			throw BusinessException.validation("vehicleId", "IN_USE", "The vehicle is already used by another driver.");
		}
	}

	private static UUID firstZone(java.util.Collection<UUID> zones) {
		return zones.isEmpty() ? null : zones.iterator().next();
	}

	private static String requireName(String name) {
		if (name == null || name.isBlank() || name.length() > 120) {
			throw BusinessException.validation("fullName", "INVALID", "A full name of up to 120 characters is required.");
		}
		return name.trim();
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
