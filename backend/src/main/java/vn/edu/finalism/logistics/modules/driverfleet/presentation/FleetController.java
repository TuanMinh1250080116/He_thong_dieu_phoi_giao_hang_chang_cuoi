package vn.edu.finalism.logistics.modules.driverfleet.presentation;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import vn.edu.finalism.logistics.modules.driverfleet.application.DriverAdminHandler;
import vn.edu.finalism.logistics.modules.driverfleet.application.DriverSelfHandler;
import vn.edu.finalism.logistics.modules.driverfleet.application.VehicleAdminHandler;
import vn.edu.finalism.logistics.modules.driverfleet.application.port.FleetRepository.DriverRow;
import vn.edu.finalism.logistics.modules.driverfleet.application.port.FleetRepository.VehicleRow;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.IdempotencyKey;
import vn.edu.finalism.logistics.shared.IfMatch;
import vn.edu.finalism.logistics.shared.Outcome;
import vn.edu.finalism.logistics.shared.PageResult;

@RestController
@RequestMapping("/api/v1")
class FleetController {

	private final VehicleAdminHandler vehicles;
	private final DriverAdminHandler drivers;
	private final DriverSelfHandler self;

	FleetController(VehicleAdminHandler vehicles, DriverAdminHandler drivers, DriverSelfHandler self) {
		this.vehicles = vehicles;
		this.drivers = drivers;
		this.self = self;
	}

	record VehicleRequest(String code, @NotNull String vehicleType, @NotNull Integer capacityWeightG,
			@NotNull Long capacityVolumeCm3, String note) {
	}

	record DriverRequest(UUID userId, @NotNull String fullName, String contact, UUID vehicleId, List<UUID> zoneIds) {
	}

	record AvailabilityRequest(@NotNull String availability) {
	}

	record PointDto(@NotNull Double lat, @NotNull Double lng) {
	}

	record LocationRequest(@NotNull @Valid PointDto point, Double accuracyM) {
	}

	record Page(List<Map<String, Object>> items, String nextCursor, Instant asOf) {
	}

	@GetMapping("/admin/vehicles")
	Page listVehicles(ActorContext actor, @RequestParam(required = false) Boolean active,
			@RequestParam(required = false) String q, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		PageResult<VehicleRow> page = vehicles.list(actor, active, q, cursor, limit);
		return new Page(page.items().stream().map(FleetController::vehicleDto).toList(), page.nextCursor(), page.asOf());
	}

	@PostMapping("/admin/vehicles")
	ResponseEntity<Map<String, Object>> createVehicle(ActorContext actor,
			@RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
			@Valid @RequestBody VehicleRequest request) {
		Outcome<VehicleRow> outcome = vehicles.create(actor, IdempotencyKey.fromHeader(idempotencyKey), draft(request));
		ResponseEntity.BodyBuilder response = outcome.replayed()
				? ResponseEntity.ok().header("Idempotent-Replayed", "true")
				: ResponseEntity.created(URI.create("/api/v1/admin/vehicles/" + outcome.value().id()));
		return response.header(HttpHeaders.ETAG, IfMatch.etag(outcome.value().version())).body(vehicleDto(outcome.value()));
	}

	@GetMapping("/admin/vehicles/{id}")
	ResponseEntity<Map<String, Object>> getVehicle(ActorContext actor, @PathVariable UUID id) {
		return ok(vehicles.get(actor, id));
	}

	@PatchMapping("/admin/vehicles/{id}")
	ResponseEntity<Map<String, Object>> updateVehicle(ActorContext actor, @PathVariable UUID id,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@Valid @RequestBody VehicleRequest request) {
		return ok(vehicles.update(actor, id, IfMatch.parse(ifMatch), draft(request)));
	}

	@PostMapping("/admin/vehicles/{id}/deactivate")
	ResponseEntity<Map<String, Object>> deactivateVehicle(ActorContext actor, @PathVariable UUID id,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
		return ok(vehicles.deactivate(actor, id, IfMatch.parse(ifMatch)));
	}

	@PostMapping("/admin/vehicles/{id}/activate")
	ResponseEntity<Map<String, Object>> activateVehicle(ActorContext actor, @PathVariable UUID id,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
		return ok(vehicles.activate(actor, id, IfMatch.parse(ifMatch)));
	}

	@GetMapping("/admin/drivers")
	Page listDrivers(ActorContext actor, @RequestParam(required = false) Boolean active,
			@RequestParam(required = false) String q, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		PageResult<DriverRow> page = drivers.list(actor, active, q, cursor, limit);
		return new Page(page.items().stream().map(FleetController::driverDto).toList(), page.nextCursor(), page.asOf());
	}

	@PostMapping("/admin/drivers")
	ResponseEntity<Map<String, Object>> createDriver(ActorContext actor,
			@RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
			@Valid @RequestBody DriverRequest request) {
		Outcome<DriverRow> outcome = drivers.create(actor, IdempotencyKey.fromHeader(idempotencyKey), draft(request));
		ResponseEntity.BodyBuilder response = outcome.replayed()
				? ResponseEntity.ok().header("Idempotent-Replayed", "true")
				: ResponseEntity.created(URI.create("/api/v1/admin/drivers/" + outcome.value().id()));
		return response.header(HttpHeaders.ETAG, IfMatch.etag(outcome.value().version())).body(driverDto(outcome.value()));
	}

	@GetMapping("/admin/driver-candidates")
	Map<String, Object> candidates(ActorContext actor) {
		return Map.of("items", drivers.candidates(actor).stream()
				.map(c -> Map.of("userId", c.userId(), "email", c.email(), "displayName", c.displayName())).toList());
	}

	@GetMapping("/admin/drivers/{id}")
	ResponseEntity<Map<String, Object>> getDriver(ActorContext actor, @PathVariable UUID id) {
		return ok(drivers.get(actor, id));
	}

	@PatchMapping("/admin/drivers/{id}")
	ResponseEntity<Map<String, Object>> updateDriver(ActorContext actor, @PathVariable UUID id,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@Valid @RequestBody DriverRequest request) {
		return ok(drivers.update(actor, id, IfMatch.parse(ifMatch), draft(request)));
	}

	@PostMapping("/admin/drivers/{id}/deactivate")
	ResponseEntity<Map<String, Object>> deactivateDriver(ActorContext actor, @PathVariable UUID id,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
		return ok(drivers.deactivate(actor, id, IfMatch.parse(ifMatch)));
	}

	@PostMapping("/admin/drivers/{id}/activate")
	ResponseEntity<Map<String, Object>> activateDriver(ActorContext actor, @PathVariable UUID id,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
		return ok(drivers.activate(actor, id, IfMatch.parse(ifMatch)));
	}

	@GetMapping("/me/driver")
	ResponseEntity<Map<String, Object>> me(ActorContext actor) {
		return profile(self.me(actor));
	}

	@PutMapping("/me/driver/availability")
	ResponseEntity<Map<String, Object>> availability(ActorContext actor,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@Valid @RequestBody AvailabilityRequest request) {
		return profile(self.changeAvailability(actor, IfMatch.parse(ifMatch), request.availability()));
	}

	@PutMapping("/me/driver/location")
	ResponseEntity<Void> location(ActorContext actor, @Valid @RequestBody LocationRequest request) {
		self.updateLocation(actor, request.point().lat(), request.point().lng(), request.accuracyM());
		return ResponseEntity.noContent().build();
	}

	private static ResponseEntity<Map<String, Object>> profile(DriverSelfHandler.Profile profile) {
		Map<String, Object> dto = new LinkedHashMap<>(driverDto(profile.driver()));
		dto.put("pendingAssignments", profile.pendingAssignments());
		dto.put("location", profile.location() == null ? null : Map.of("lat", profile.location().lat(), "lng",
				profile.location().lng(), "accuracyM", profile.location().accuracyM() == null ? 0 : profile.location().accuracyM(),
				"recordedAt", profile.location().recordedAt()));
		return ResponseEntity.ok().header(HttpHeaders.ETAG, IfMatch.etag(profile.driver().version())).body(dto);
	}

	private static ResponseEntity<Map<String, Object>> ok(VehicleRow vehicle) {
		return ResponseEntity.ok().header(HttpHeaders.ETAG, IfMatch.etag(vehicle.version())).body(vehicleDto(vehicle));
	}

	private static ResponseEntity<Map<String, Object>> ok(DriverRow driver) {
		return ResponseEntity.ok().header(HttpHeaders.ETAG, IfMatch.etag(driver.version())).body(driverDto(driver));
	}

	private static VehicleAdminHandler.Draft draft(VehicleRequest r) {
		return new VehicleAdminHandler.Draft(r.code(), r.vehicleType(), r.capacityWeightG(), r.capacityVolumeCm3(), r.note());
	}

	private static DriverAdminHandler.Draft draft(DriverRequest r) {
		return new DriverAdminHandler.Draft(r.userId(), r.fullName(), r.contact(), r.vehicleId(), r.zoneIds());
	}

	private static Map<String, Object> vehicleDto(VehicleRow v) {
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("id", v.id());
		dto.put("code", v.code());
		dto.put("vehicleType", v.vehicleType());
		dto.put("capacityWeightG", v.capacityWeightG());
		dto.put("capacityVolumeCm3", v.capacityVolumeCm3());
		dto.put("active", v.active());
		dto.put("note", v.note());
		dto.put("version", v.version());
		dto.put("createdAt", v.createdAt());
		return dto;
	}

	private static Map<String, Object> driverDto(DriverRow d) {
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("id", d.id());
		dto.put("userId", d.userId());
		dto.put("code", d.code());
		dto.put("fullName", d.fullName());
		dto.put("contact", d.contact());
		dto.put("active", d.active());
		dto.put("availability", d.availability());
		dto.put("vehicle", d.vehicleId() == null ? null
				: Map.of("id", d.vehicleId(), "code", d.vehicleCode(), "vehicleType", d.vehicleType()));
		dto.put("zoneIds", d.zoneIds());
		dto.put("version", d.version());
		dto.put("createdAt", d.createdAt());
		return dto;
	}
}
