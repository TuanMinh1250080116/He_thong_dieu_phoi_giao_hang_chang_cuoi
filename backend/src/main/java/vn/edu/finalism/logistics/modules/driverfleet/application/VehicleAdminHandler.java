package vn.edu.finalism.logistics.modules.driverfleet.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.audit.application.AuditAction;
import vn.edu.finalism.logistics.modules.audit.application.AuditEntry;
import vn.edu.finalism.logistics.modules.audit.application.AuditWriter;
import vn.edu.finalism.logistics.modules.driverfleet.application.port.FleetRepository;
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
public class VehicleAdminHandler {

	public record Draft(String code, String vehicleType, Integer capacityWeightG, Long capacityVolumeCm3, String note) {
	}

	private final FleetRepository fleet;
	private final IdempotencyService idempotency;
	private final RequestFingerprinter fingerprinter;
	private final AuditWriter audit;
	private final ClockPort clock;

	VehicleAdminHandler(FleetRepository fleet, IdempotencyService idempotency, RequestFingerprinter fingerprinter,
			AuditWriter audit, ClockPort clock) {
		this.fleet = fleet;
		this.idempotency = idempotency;
		this.fingerprinter = fingerprinter;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public PageResult<VehicleRow> list(ActorContext actor, Boolean active, String query, String cursor, Integer limit) {
		actor.requirePermission("fleet.manage");
		int size = PageCursor.normalizeLimit(limit);
		List<VehicleRow> rows = fleet.listVehicles(active, blankToNull(query),
				cursor == null || cursor.isBlank() ? null : PageCursor.decode(cursor), size + 1);
		boolean more = rows.size() > size;
		List<VehicleRow> page = more ? rows.subList(0, size) : rows;
		String next = more ? new PageCursor(page.get(page.size() - 1).createdAt(), page.get(page.size() - 1).id())
				.encode() : null;
		return new PageResult<>(List.copyOf(page), next, clock.now());
	}

	@Transactional(readOnly = true)
	public VehicleRow get(ActorContext actor, UUID id) {
		actor.requirePermission("fleet.manage");
		return fleet.findVehicle(id).orElseThrow(() -> BusinessException.notFound("Vehicle"));
	}

	@Transactional
	public Outcome<VehicleRow> create(ActorContext actor, IdempotencyKey key, Draft draft) {
		actor.requirePermission("fleet.manage");
		String code = draft.code() == null ? "" : draft.code().trim().toUpperCase();
		validate(code, draft);
		byte[] fingerprint = fingerprinter.fingerprint("POST /admin/vehicles", null, draft);
		IdempotencyClaim claim = idempotency.claim(actor.userId(), key, "CREATE_VEHICLE", fingerprint);
		if (claim instanceof IdempotencyClaim.Replay replay) {
			return Outcome.replay(fleet.findVehicle(replay.result().resultRef())
					.orElseThrow(() -> BusinessException.notFound("Vehicle")));
		}
		if (fleet.vehicleCodeExists(code)) {
			throw BusinessException.validation("code", "DUPLICATE", "This vehicle code is already in use.");
		}
		Instant now = clock.now().truncatedTo(ChronoUnit.MILLIS);
		UUID id = UUID.randomUUID();
		fleet.insertVehicle(id, code, draft.vehicleType().trim(), draft.capacityWeightG(), draft.capacityVolumeCm3(),
				blankToNull(draft.note()), now);
		audit.append(AuditEntry.forUser(actor, AuditAction.VEHICLE_CREATED, "VEHICLE", id, null, null,
				Map.of("code", code, "type", draft.vehicleType().trim()), null, null));
		idempotency.complete(actor.userId(), key, "VEHICLE", id, 0L);
		return Outcome.fresh(fleet.findVehicle(id).orElseThrow());
	}

	@Transactional
	public VehicleRow update(ActorContext actor, UUID id, long expectedVersion, Draft draft) {
		actor.requirePermission("fleet.manage");
		VehicleRow current = lock(id, expectedVersion);
		validate(current.code(), draft);
		if (!fleet.updateVehicle(id, draft.vehicleType().trim(), draft.capacityWeightG(), draft.capacityVolumeCm3(),
				blankToNull(draft.note()), expectedVersion, clock.now())) {
			throw BusinessException.versionConflict(expectedVersion + 1);
		}
		Map<String, Object> before = new LinkedHashMap<>();
		before.put("type", current.vehicleType());
		before.put("capacityWeightG", current.capacityWeightG());
		before.put("capacityVolumeCm3", current.capacityVolumeCm3());
		Map<String, Object> after = new LinkedHashMap<>();
		after.put("type", draft.vehicleType().trim());
		after.put("capacityWeightG", draft.capacityWeightG());
		after.put("capacityVolumeCm3", draft.capacityVolumeCm3());
		audit.append(AuditEntry.forUser(actor, AuditAction.VEHICLE_UPDATED, "VEHICLE", id, null, before, after, null, null));
		return fleet.findVehicle(id).orElseThrow();
	}

	@Transactional
	public VehicleRow deactivate(ActorContext actor, UUID id, long expectedVersion) {
		actor.requirePermission("fleet.manage");
		VehicleRow current = lock(id, expectedVersion);
		if (!current.active()) {
			throw BusinessException.stateNotAllowed("The vehicle is already inactive.");
		}
		fleet.driverUsingVehicle(id, null).ifPresent(driverId -> {
			List<UUID> open = fleet.openAssignmentIdsOfDriver(driverId);
			if (!open.isEmpty()) {
				throw new BusinessException(ErrorCode.STATE_TRANSITION_NOT_ALLOWED,
						"The vehicle is used by a driver with an open assignment.",
						Map.of("blockingAssignmentIds", open.stream().map(UUID::toString).toList()));
			}
		});
		change(id, false, expectedVersion);
		audit.append(AuditEntry.forUser(actor, AuditAction.VEHICLE_DEACTIVATED, "VEHICLE", id, null,
				Map.of("active", true), Map.of("active", false), null, null));
		return fleet.findVehicle(id).orElseThrow();
	}

	@Transactional
	public VehicleRow activate(ActorContext actor, UUID id, long expectedVersion) {
		actor.requirePermission("fleet.manage");
		VehicleRow current = lock(id, expectedVersion);
		if (current.active()) {
			throw BusinessException.stateNotAllowed("The vehicle is already active.");
		}
		change(id, true, expectedVersion);
		audit.append(AuditEntry.forUser(actor, AuditAction.VEHICLE_ACTIVATED, "VEHICLE", id, null,
				Map.of("active", false), Map.of("active", true), null, null));
		return fleet.findVehicle(id).orElseThrow();
	}

	private void change(UUID id, boolean active, long expectedVersion) {
		if (!fleet.setVehicleActive(id, active, expectedVersion, clock.now())) {
			throw BusinessException.versionConflict(expectedVersion + 1);
		}
	}

	private VehicleRow lock(UUID id, long expectedVersion) {
		VehicleRow current = fleet.findVehicleForUpdate(id).orElseThrow(() -> BusinessException.notFound("Vehicle"));
		if (current.version() != expectedVersion) {
			throw BusinessException.versionConflict(current.version());
		}
		return current;
	}

	private static void validate(String code, Draft draft) {
		if (code.isEmpty() || code.length() > 32) {
			throw BusinessException.validation("code", "INVALID", "A vehicle code of up to 32 characters is required.");
		}
		if (draft.vehicleType() == null || draft.vehicleType().isBlank() || draft.vehicleType().length() > 40) {
			throw BusinessException.validation("vehicleType", "INVALID", "A vehicle type is required.");
		}
		if (draft.capacityWeightG() == null || draft.capacityWeightG() <= 0 || draft.capacityWeightG() > 100_000_000) {
			throw BusinessException.validation("capacityWeightG", "OUT_OF_RANGE",
					"The weight capacity must be a positive number of grams.");
		}
		if (draft.capacityVolumeCm3() == null || draft.capacityVolumeCm3() <= 0
				|| draft.capacityVolumeCm3() > 100_000_000_000L) {
			throw BusinessException.validation("capacityVolumeCm3", "OUT_OF_RANGE",
					"The volume capacity must be a positive number of cubic centimeters.");
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
