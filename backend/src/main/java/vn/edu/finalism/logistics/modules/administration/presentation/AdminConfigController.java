package vn.edu.finalism.logistics.modules.administration.presentation;

import java.math.BigDecimal;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import vn.edu.finalism.logistics.modules.administration.application.DispatchRuleAdminHandler;
import vn.edu.finalism.logistics.modules.administration.application.PodPolicyAdminHandler;
import vn.edu.finalism.logistics.modules.administration.application.ZoneAdminHandler;
import vn.edu.finalism.logistics.modules.administration.application.port.ConfigAdminRepository.PolicyRow;
import vn.edu.finalism.logistics.modules.administration.application.port.ConfigAdminRepository.RuleRow;
import vn.edu.finalism.logistics.modules.administration.application.port.ConfigAdminRepository.ZoneRow;
import vn.edu.finalism.logistics.modules.administration.application.port.ConfigAdminRepository.ZoneVersionRow;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.IdempotencyKey;
import vn.edu.finalism.logistics.shared.Outcome;

@RestController
@RequestMapping("/api/v1/admin")
class AdminConfigController {

	private final ZoneAdminHandler zones;
	private final DispatchRuleAdminHandler rules;
	private final PodPolicyAdminHandler policies;

	AdminConfigController(ZoneAdminHandler zones, DispatchRuleAdminHandler rules, PodPolicyAdminHandler policies) {
		this.zones = zones;
		this.rules = rules;
		this.policies = policies;
	}

	record ZoneRequest(@NotNull String code, @NotNull String name) {
	}

	record ZoneVersionRequest(@NotNull Map<String, Object> boundary) {
	}

	record Weights(BigDecimal distance, BigDecimal capacityFit, BigDecimal availability, BigDecimal zoneCompat) {
	}

	record RuleRequest(UUID zoneId, @NotNull String name, @NotNull @Valid Weights weights, Integer ttlSeconds,
			Integer searchRadiusM, Integer maxLocationAgeSeconds, Integer persistTopN) {
	}

	record Expression(String op, List<String> items) {
	}

	record PolicyRequest(UUID zoneId, @NotNull String name, @NotNull @Valid Expression evidenceExpression,
			Integer maxGpsAccuracyM, String recipientNameRule) {
	}

	@GetMapping("/zone-options")
	Map<String, Object> zoneOptions(ActorContext actor) {
		return Map.of("items", zones.options(actor).stream()
				.map(z -> Map.of("id", z.id(), "code", z.code(), "name", z.name())).toList());
	}

	@GetMapping("/zones")
	Map<String, Object> listZones(ActorContext actor) {
		return Map.of("items", zones.list(actor).stream().map(AdminConfigController::zoneDto).toList());
	}

	@PostMapping("/zones")
	ResponseEntity<Map<String, Object>> createZone(ActorContext actor,
			@RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
			@Valid @RequestBody ZoneRequest request) {
		Outcome<ZoneRow> outcome = zones.create(actor, IdempotencyKey.fromHeader(idempotencyKey), request.code(),
				request.name());
		return created(outcome, "/api/v1/admin/zones/", outcome.value().id(), zoneDto(outcome.value()));
	}

	record ZoneStateRequest(@NotNull String reason) {
	}

	@PostMapping("/zones/{zoneId}/deactivate")
	Map<String, Object> deactivateZone(ActorContext actor, @PathVariable UUID zoneId,
			@Valid @RequestBody ZoneStateRequest request) {
		return zoneDto(zones.setActive(actor, zoneId, false, request.reason()));
	}

	@PostMapping("/zones/{zoneId}/activate")
	Map<String, Object> activateZoneState(ActorContext actor, @PathVariable UUID zoneId,
			@Valid @RequestBody ZoneStateRequest request) {
		return zoneDto(zones.setActive(actor, zoneId, true, request.reason()));
	}

	@GetMapping("/zones/{zoneId}/versions")
	Map<String, Object> listVersions(ActorContext actor, @PathVariable UUID zoneId) {
		return Map.of("items", zones.versions(actor, zoneId).stream().map(AdminConfigController::zoneVersionDto).toList());
	}

	@PostMapping("/zones/{zoneId}/versions")
	ResponseEntity<Map<String, Object>> addVersion(ActorContext actor, @PathVariable UUID zoneId,
			@RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
			@Valid @RequestBody ZoneVersionRequest request) {
		Outcome<ZoneVersionRow> outcome = zones.addVersion(actor, IdempotencyKey.fromHeader(idempotencyKey), zoneId,
				request.boundary());
		return created(outcome, "/api/v1/admin/zone-versions/", outcome.value().id(), zoneVersionDto(outcome.value()));
	}

	@PostMapping("/zone-versions/{versionId}/activate")
	ResponseEntity<Map<String, Object>> activateZone(ActorContext actor, @PathVariable UUID versionId,
			@RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey) {
		Outcome<ZoneVersionRow> outcome = zones.activate(actor, IdempotencyKey.fromHeader(idempotencyKey), versionId);
		return ok(outcome, zoneVersionDto(outcome.value()));
	}

	@GetMapping("/dispatch-rules")
	Map<String, Object> listRules(ActorContext actor, @RequestParam(required = false) String status) {
		return Map.of("items", rules.list(actor, status).stream().map(AdminConfigController::ruleDto).toList());
	}

	@PostMapping("/dispatch-rules")
	ResponseEntity<Map<String, Object>> createRule(ActorContext actor,
			@RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
			@Valid @RequestBody RuleRequest request) {
		Outcome<RuleRow> outcome = rules.create(actor, IdempotencyKey.fromHeader(idempotencyKey),
				new DispatchRuleAdminHandler.Draft(request.zoneId(), request.name(), request.weights().distance(),
						request.weights().capacityFit(), request.weights().availability(), request.weights().zoneCompat(),
						request.ttlSeconds(), request.searchRadiusM(), request.maxLocationAgeSeconds(),
						request.persistTopN()));
		return created(outcome, "/api/v1/admin/dispatch-rules/", outcome.value().id(), ruleDto(outcome.value()));
	}

	record ActivateRequest(java.time.Instant effectiveFrom) {
	}

	@PostMapping("/dispatch-rules/{ruleId}/activate")
	ResponseEntity<Map<String, Object>> activateRule(ActorContext actor, @PathVariable UUID ruleId,
			@RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
			@RequestBody(required = false) ActivateRequest request) {
		Outcome<RuleRow> outcome = rules.activate(actor, IdempotencyKey.fromHeader(idempotencyKey), ruleId,
				request == null ? null : request.effectiveFrom());
		return ok(outcome, ruleDto(outcome.value()));
	}

	@PostMapping("/dispatch-rules/{ruleId}/unschedule")
	Map<String, Object> unscheduleRule(ActorContext actor, @PathVariable UUID ruleId) {
		return ruleDto(rules.unschedule(actor, ruleId));
	}

	@PostMapping("/pod-policies/{policyId}/unschedule")
	Map<String, Object> unschedulePolicy(ActorContext actor, @PathVariable UUID policyId) {
		return policyDto(policies.unschedule(actor, policyId));
	}

	@GetMapping("/pod-policies")
	Map<String, Object> listPolicies(ActorContext actor, @RequestParam(required = false) String status) {
		return Map.of("items", policies.list(actor, status).stream().map(AdminConfigController::policyDto).toList());
	}

	@PostMapping("/pod-policies")
	ResponseEntity<Map<String, Object>> createPolicy(ActorContext actor,
			@RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
			@Valid @RequestBody PolicyRequest request) {
		Outcome<PolicyRow> outcome = policies.create(actor, IdempotencyKey.fromHeader(idempotencyKey),
				new PodPolicyAdminHandler.Draft(request.zoneId(), request.name(), request.evidenceExpression().op(),
						request.evidenceExpression().items(), request.maxGpsAccuracyM(), request.recipientNameRule()));
		return created(outcome, "/api/v1/admin/pod-policies/", outcome.value().id(), policyDto(outcome.value()));
	}

	@PostMapping("/pod-policies/{policyId}/activate")
	ResponseEntity<Map<String, Object>> activatePolicy(ActorContext actor, @PathVariable UUID policyId,
			@RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
			@RequestBody(required = false) ActivateRequest request) {
		Outcome<PolicyRow> outcome = policies.activate(actor, IdempotencyKey.fromHeader(idempotencyKey), policyId,
				request == null ? null : request.effectiveFrom());
		return ok(outcome, policyDto(outcome.value()));
	}

	private static ResponseEntity<Map<String, Object>> created(Outcome<?> outcome, String base, UUID id,
			Map<String, Object> body) {
		ResponseEntity.BodyBuilder response = outcome.replayed()
				? ResponseEntity.ok().header("Idempotent-Replayed", "true")
				: ResponseEntity.created(URI.create(base + id));
		return response.body(body);
	}

	private static ResponseEntity<Map<String, Object>> ok(Outcome<?> outcome, Map<String, Object> body) {
		ResponseEntity.BodyBuilder response = ResponseEntity.ok();
		if (outcome.replayed()) {
			response.header("Idempotent-Replayed", "true");
		}
		return response.body(body);
	}

	private static Map<String, Object> zoneDto(ZoneRow z) {
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("id", z.id());
		dto.put("code", z.code());
		dto.put("name", z.name());
		dto.put("active", z.active());
		dto.put("activeVersionNo", z.activeVersionNo());
		dto.put("activeVersionId", z.activeVersionId());
		dto.put("versionCount", z.versionCount());
		return dto;
	}

	private static Map<String, Object> zoneVersionDto(ZoneVersionRow v) {
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("id", v.id());
		dto.put("zoneId", v.zoneId());
		dto.put("versionNo", v.versionNo());
		dto.put("status", v.status());
		dto.put("effectiveFrom", v.effectiveFrom());
		dto.put("activatedAt", v.activatedAt());
		dto.put("retiredAt", v.retiredAt());
		dto.put("createdAt", v.createdAt());
		dto.put("boundaryGeoJson", v.boundaryGeoJson());
		return dto;
	}

	private static Map<String, Object> ruleDto(RuleRow r) {
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("id", r.id());
		dto.put("zoneId", r.zoneId());
		dto.put("versionNo", r.versionNo());
		dto.put("name", r.name());
		dto.put("status", r.status());
		dto.put("weights", Map.of("distance", r.wDistance(), "capacityFit", r.wCapacityFit(), "availability",
				r.wAvailability(), "zoneCompat", r.wZoneCompat()));
		dto.put("ttlSeconds", r.ttlSeconds());
		dto.put("searchRadiusM", r.searchRadiusM());
		dto.put("maxLocationAgeSeconds", r.maxLocationAgeSeconds());
		dto.put("persistTopN", r.persistTopN());
		dto.put("effectiveFrom", r.effectiveFrom());
		dto.put("activatedAt", r.activatedAt());
		dto.put("retiredAt", r.retiredAt());
		dto.put("createdAt", r.createdAt());
		return dto;
	}

	private static Map<String, Object> policyDto(PolicyRow p) {
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("id", p.id());
		dto.put("zoneId", p.zoneId());
		dto.put("versionNo", p.versionNo());
		dto.put("name", p.name());
		dto.put("status", p.status());
		dto.put("evidenceExpression", Map.of("op", p.evidenceOp(), "items", p.evidenceItems()));
		dto.put("requiresGps", true);
		dto.put("requiresServerTimestamp", true);
		dto.put("maxGpsAccuracyM", p.maxGpsAccuracyM());
		dto.put("recipientNameRule", p.recipientNameRule());
		dto.put("effectiveFrom", p.effectiveFrom());
		dto.put("activatedAt", p.activatedAt());
		dto.put("retiredAt", p.retiredAt());
		dto.put("createdAt", p.createdAt());
		return dto;
	}
}
