package vn.edu.finalism.logistics.modules.administration.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;
import vn.edu.finalism.logistics.modules.administration.application.port.ConfigAdminRepository;
import vn.edu.finalism.logistics.modules.administration.application.port.ConfigAdminRepository.GeometryCheck;
import vn.edu.finalism.logistics.modules.administration.application.port.ConfigAdminRepository.ZoneRow;
import vn.edu.finalism.logistics.modules.administration.application.port.ConfigAdminRepository.ZoneVersionRow;
import vn.edu.finalism.logistics.modules.administration.domain.ConfigValidation;
import vn.edu.finalism.logistics.modules.audit.application.AuditAction;
import vn.edu.finalism.logistics.modules.audit.application.AuditEntry;
import vn.edu.finalism.logistics.modules.audit.application.AuditWriter;
import vn.edu.finalism.logistics.modules.identityaccess.application.UserDirectoryPort;
import vn.edu.finalism.logistics.platform.idempotency.IdempotencyClaim;
import vn.edu.finalism.logistics.platform.idempotency.IdempotencyService;
import vn.edu.finalism.logistics.platform.idempotency.RequestFingerprinter;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.ErrorCode;
import vn.edu.finalism.logistics.shared.IdempotencyKey;
import vn.edu.finalism.logistics.shared.Outcome;

@Service
public class ZoneAdminHandler {

	private final ConfigAdminRepository config;
	private final UserDirectoryPort directory;
	private final IdempotencyService idempotency;
	private final RequestFingerprinter fingerprinter;
	private final AuditWriter audit;
	private final ObjectMapper mapper;
	private final ClockPort clock;

	ZoneAdminHandler(ConfigAdminRepository config, UserDirectoryPort directory, IdempotencyService idempotency,
			RequestFingerprinter fingerprinter, AuditWriter audit, ObjectMapper mapper, ClockPort clock) {
		this.config = config;
		this.directory = directory;
		this.idempotency = idempotency;
		this.fingerprinter = fingerprinter;
		this.audit = audit;
		this.mapper = mapper;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<ZoneRow> list(ActorContext actor) {
		actor.requirePermission("config.zone.manage");
		return config.listZones(actor.operationScopeIds());
	}

	@Transactional(readOnly = true)
	public List<ConfigAdminRepository.ZoneOption> options(ActorContext actor) {
		if (!actor.hasPermission("config.zone.manage") && !actor.hasPermission("iam.role.manage")) {
			throw new BusinessException(ErrorCode.FORBIDDEN, "You do not have permission for this action.");
		}
		return config.zoneOptions();
	}

	@Transactional
	public Outcome<ZoneRow> create(ActorContext actor, IdempotencyKey key, String code, String name) {
		actor.requirePermission("config.zone.manage");
		String normalized = code == null ? null : code.trim().toUpperCase();
		ConfigValidation.validateZoneCode(normalized);
		if (name == null || name.isBlank() || name.length() > 120) {
			throw BusinessException.validation("name", "INVALID", "A name of up to 120 characters is required.");
		}
		byte[] fingerprint = fingerprinter.fingerprint("POST /admin/zones", null,
				Map.of("code", normalized, "name", name.trim()));
		IdempotencyClaim claim = idempotency.claim(actor.userId(), key, "CREATE_ZONE", fingerprint);
		if (claim instanceof IdempotencyClaim.Replay replay) {
			return Outcome.replay(config.findZone(replay.result().resultRef())
					.orElseThrow(() -> BusinessException.notFound("Zone")));
		}
		if (config.zoneCodeExists(normalized)) {
			throw BusinessException.validation("code", "DUPLICATE", "This zone code is already in use.");
		}
		Instant now = clock.now();
		UUID id = UUID.randomUUID();
		config.insertZone(id, normalized, name.trim(), now);
		directory.grantScope(actor.userId(), id, actor.userId());
		audit.append(AuditEntry.forUser(actor, AuditAction.ZONE_CREATED, "ZONE", id, id, null,
				Map.of("code", normalized, "name", name.trim()), null, null));
		idempotency.complete(actor.userId(), key, "ZONE", id, 0L);
		return Outcome.fresh(config.findZone(id).orElseThrow());
	}

	@Transactional
	public ZoneRow setActive(ActorContext actor, UUID zoneId, boolean active, String reason) {
		actor.requirePermission("config.zone.manage");
		actor.requireScope(zoneId);
		String text = reason == null ? "" : reason.trim();
		if (text.isEmpty() || text.length() > 500) {
			throw BusinessException.validation("reason", "REQUIRED", "A reason of up to 500 characters is required.");
		}
		ZoneRow zone = config.findZone(zoneId).orElseThrow(() -> BusinessException.notFound("Zone"));
		if (zone.active() == active) {
			throw BusinessException.stateNotAllowed(active ? "The zone is already active." : "The zone is already inactive.");
		}
		if (!active) {
			ConfigAdminRepository.ZoneUsage usage = config.zoneUsage(zoneId);
			if (usage.openOrders() > 0 || usage.openBatches() > 0) {
				throw new BusinessException(ErrorCode.STATE_TRANSITION_NOT_ALLOWED,
						"The zone still has open orders or batches.",
						Map.of("blockingOrders", usage.openOrders(), "blockingBatches", usage.openBatches()));
			}
		}
		config.setZoneActive(zoneId, active, clock.now());
		audit.append(AuditEntry.forUser(actor, active ? AuditAction.ZONE_ACTIVATED : AuditAction.ZONE_DEACTIVATED,
				"ZONE", zoneId, zoneId, Map.of("active", zone.active()), Map.of("active", active), text, null));
		return config.findZone(zoneId).orElseThrow();
	}

	@Transactional(readOnly = true)
	public List<ZoneVersionRow> versions(ActorContext actor, UUID zoneId) {
		actor.requirePermission("config.zone.manage");
		actor.requireScope(zoneId);
		return config.listZoneVersions(zoneId);
	}

	@Transactional
	public Outcome<ZoneVersionRow> addVersion(ActorContext actor, IdempotencyKey key, UUID zoneId,
			Map<String, Object> boundary) {
		actor.requirePermission("config.zone.manage");
		actor.requireScope(zoneId);
		if (boundary == null || boundary.isEmpty()) {
			throw BusinessException.validation("boundary", "REQUIRED", "A GeoJSON boundary is required.");
		}
		String geoJson = mapper.writeValueAsString(boundary);
		byte[] fingerprint = fingerprinter.fingerprint("POST /admin/zones/" + zoneId + "/versions", null, boundary);
		IdempotencyClaim claim = idempotency.claim(actor.userId(), key, "CREATE_ZONE_VERSION", fingerprint);
		if (claim instanceof IdempotencyClaim.Replay replay) {
			return Outcome.replay(config.listZoneVersions(zoneId).stream()
					.filter(v -> v.id().equals(replay.result().resultRef())).findFirst()
					.orElseThrow(() -> BusinessException.notFound("Zone version")));
		}
		GeometryCheck check = config.checkGeometry(geoJson);
		if (!check.valid()) {
			throw BusinessException.validation("boundary", "INVALID_GEOMETRY", check.reason());
		}
		if (!"POLYGON".equalsIgnoreCase(check.type()) && !"MULTIPOLYGON".equalsIgnoreCase(check.type())) {
			throw BusinessException.validation("boundary", "WRONG_TYPE", "The boundary must be a Polygon or MultiPolygon.");
		}
		if (check.minLng() < -180 || check.maxLng() > 180 || check.minLat() < -90 || check.maxLat() > 90) {
			throw BusinessException.validation("boundary", "OUT_OF_RANGE",
					"Coordinates must be longitude -180..180 and latitude -90..90.");
		}
		if (check.areaSqM() <= 0) {
			throw BusinessException.validation("boundary", "EMPTY_AREA", "The boundary must enclose an area.");
		}

		Instant now = clock.now();
		UUID id = UUID.randomUUID();
		int versionNo = config.nextZoneVersionNo(zoneId);
		config.insertZoneVersion(id, zoneId, versionNo, geoJson, actor.userId(), now);
		audit.append(AuditEntry.forUser(actor, AuditAction.ZONE_VERSION_CREATED, "ZONE_VERSION", id, zoneId, null,
				Map.of("zoneId", zoneId.toString(), "versionNo", versionNo, "status", "DRAFT"), null, null));
		idempotency.complete(actor.userId(), key, "ZONE_VERSION", id, 0L);
		return Outcome.fresh(config.listZoneVersions(zoneId).stream().filter(v -> v.id().equals(id)).findFirst()
				.orElseThrow());
	}

	@Transactional
	public Outcome<ZoneVersionRow> activate(ActorContext actor, IdempotencyKey key, UUID versionId) {
		actor.requirePermission("config.zone.manage");
		byte[] fingerprint = fingerprinter.fingerprint("POST /admin/zone-versions/" + versionId + "/activate", null, null);
		IdempotencyClaim claim = idempotency.claim(actor.userId(), key, "ACTIVATE_ZONE_VERSION", fingerprint);
		if (claim instanceof IdempotencyClaim.Replay replay) {
			ZoneVersionRow existing = config.findZoneVersionForUpdate(replay.result().resultRef())
					.orElseThrow(() -> BusinessException.notFound("Zone version"));
			return Outcome.replay(existing);
		}
		config.lockZoneActivation();
		ZoneVersionRow version = config.findZoneVersionForUpdate(versionId)
				.orElseThrow(() -> BusinessException.notFound("Zone version"));
		actor.requireScope(version.zoneId());
		if (!"DRAFT".equals(version.status())) {
			throw BusinessException.stateNotAllowed("Only a draft version can be activated.");
		}
		List<UUID> overlapping = config.overlappingActiveZones(versionId);
		if (!overlapping.isEmpty()) {
			throw new BusinessException(ErrorCode.STATE_TRANSITION_NOT_ALLOWED,
					"This boundary overlaps another active zone.",
					Map.of("overlappingZoneIds", overlapping.stream().map(UUID::toString).toList()));
		}

		Instant now = clock.now();
		Integer previous = config.retireActiveZoneVersion(version.zoneId(), now).orElse(null);
		config.activateZoneVersion(versionId, now);
		config.touchZone(version.zoneId(), now);

		Map<String, Object> before = new LinkedHashMap<>();
		before.put("activeVersionNo", previous);
		audit.append(AuditEntry.forUser(actor, AuditAction.ZONE_CHANGED, "ZONE", version.zoneId(), version.zoneId(),
				before, Map.of("activeVersionNo", version.versionNo(), "versionId", versionId.toString()), null, null));
		idempotency.complete(actor.userId(), key, "ZONE_VERSION", versionId, 0L);
		return Outcome.fresh(config.findZoneVersionForUpdate(versionId).orElseThrow());
	}
}
