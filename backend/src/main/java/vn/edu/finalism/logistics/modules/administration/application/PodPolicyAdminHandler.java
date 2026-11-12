package vn.edu.finalism.logistics.modules.administration.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.administration.application.port.ConfigAdminRepository;
import vn.edu.finalism.logistics.modules.administration.application.port.ConfigAdminRepository.Kind;
import vn.edu.finalism.logistics.modules.administration.application.port.ConfigAdminRepository.PolicyRow;
import vn.edu.finalism.logistics.modules.administration.application.port.ConfigAdminRepository.VersionHeader;
import vn.edu.finalism.logistics.modules.administration.domain.ConfigValidation;
import vn.edu.finalism.logistics.modules.audit.application.AuditAction;
import vn.edu.finalism.logistics.modules.audit.application.AuditEntry;
import vn.edu.finalism.logistics.modules.audit.application.AuditWriter;
import vn.edu.finalism.logistics.platform.idempotency.IdempotencyClaim;
import vn.edu.finalism.logistics.platform.idempotency.IdempotencyService;
import vn.edu.finalism.logistics.platform.idempotency.RequestFingerprinter;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.IdempotencyKey;
import vn.edu.finalism.logistics.shared.Outcome;

@Service
public class PodPolicyAdminHandler {

	private final ConfigAdminRepository config;
	private final IdempotencyService idempotency;
	private final RequestFingerprinter fingerprinter;
	private final AuditWriter audit;
	private final ClockPort clock;

	PodPolicyAdminHandler(ConfigAdminRepository config, IdempotencyService idempotency,
			RequestFingerprinter fingerprinter, AuditWriter audit, ClockPort clock) {
		this.config = config;
		this.idempotency = idempotency;
		this.fingerprinter = fingerprinter;
		this.audit = audit;
		this.clock = clock;
	}

	public record Draft(UUID zoneId, String name, String evidenceOp, List<String> evidenceItems,
			Integer maxGpsAccuracyM, String recipientNameRule) {
	}

	@Transactional(readOnly = true)
	public List<PolicyRow> list(ActorContext actor, String status) {
		actor.requirePermission("config.rule.manage");
		return config.listPolicies(actor.operationScopeIds(), status);
	}

	@Transactional
	public Outcome<PolicyRow> create(ActorContext actor, IdempotencyKey key, Draft draft) {
		actor.requirePermission("config.rule.manage");
		requireScope(actor, draft.zoneId());
		ConfigValidation.validatePodPolicy(draft.name(), draft.evidenceOp(), draft.evidenceItems(),
				draft.maxGpsAccuracyM(), draft.recipientNameRule());
		byte[] fingerprint = fingerprinter.fingerprint("POST /admin/pod-policies", null, draft);
		IdempotencyClaim claim = idempotency.claim(actor.userId(), key, "CREATE_POD_POLICY", fingerprint);
		if (claim instanceof IdempotencyClaim.Replay replay) {
			return Outcome.replay(config.findPolicy(replay.result().resultRef())
					.orElseThrow(() -> BusinessException.notFound("POD policy")));
		}

		Instant now = clock.now();
		UUID id = UUID.randomUUID();
		int versionNo = config.nextVersionNo(Kind.POD_POLICY, draft.zoneId());
		config.insertPolicy(new PolicyRow(id, draft.zoneId(), versionNo, draft.name().trim(), "DRAFT",
				draft.evidenceOp(), List.copyOf(draft.evidenceItems()), draft.maxGpsAccuracyM(),
				draft.recipientNameRule(), now, null, null, now), actor.userId(), now);
		audit.append(AuditEntry.forUser(actor, AuditAction.POD_POLICY_CREATED, "POD_POLICY", id, draft.zoneId(), null,
				Map.of("versionNo", versionNo, "status", "DRAFT"), null, null));
		idempotency.complete(actor.userId(), key, "POD_POLICY", id, 0L);
		return Outcome.fresh(config.findPolicy(id).orElseThrow());
	}

	@Transactional
	public Outcome<PolicyRow> activate(ActorContext actor, IdempotencyKey key, UUID policyId, Instant effectiveFrom) {
		actor.requirePermission("config.rule.manage");
		byte[] fingerprint = fingerprinter.fingerprint("POST /admin/pod-policies/" + policyId + "/activate", null,
				Map.of("effectiveFrom", effectiveFrom == null ? "" : effectiveFrom.toString()));
		IdempotencyClaim claim = idempotency.claim(actor.userId(), key, "ACTIVATE_POD_POLICY", fingerprint);
		if (claim instanceof IdempotencyClaim.Replay replay) {
			return Outcome.replay(config.findPolicy(replay.result().resultRef())
					.orElseThrow(() -> BusinessException.notFound("POD policy")));
		}
		VersionHeader header = config.findHeaderForUpdate(Kind.POD_POLICY, policyId)
				.orElseThrow(() -> BusinessException.notFound("POD policy"));
		requireScope(actor, header.zoneId());
		if (!"DRAFT".equals(header.status())) {
			throw BusinessException.stateNotAllowed("Only a draft version can be activated.");
		}
		Instant now = clock.now();
		config.lockVersionActivation();
		if (effectiveFrom != null && effectiveFrom.isAfter(now)) {
			ScheduleRules.requireValid(effectiveFrom, now);
			if (config.scheduledExists(Kind.POD_POLICY, header.zoneId())) {
				throw BusinessException.stateNotAllowed(
						"Another version is already scheduled for this scope. Cancel its schedule first.");
			}
			config.schedule(Kind.POD_POLICY, policyId, effectiveFrom);
			audit.append(AuditEntry.forUser(actor, AuditAction.POD_POLICY_SCHEDULED, "POD_POLICY", policyId,
					header.zoneId(), null, Map.of("versionNo", header.versionNo(), "effectiveFrom", effectiveFrom.toString()),
					null, null));
			idempotency.complete(actor.userId(), key, "POD_POLICY", policyId, 0L);
			return Outcome.fresh(config.findPolicy(policyId).orElseThrow());
		}
		Integer previous = config.retireActive(Kind.POD_POLICY, header.zoneId(), now).orElse(null);
		config.activate(Kind.POD_POLICY, policyId, now);

		Map<String, Object> before = new LinkedHashMap<>();
		before.put("activeVersionNo", previous);
		audit.append(AuditEntry.forUser(actor, AuditAction.POD_POLICY_CHANGED, "POD_POLICY", policyId,
				header.zoneId(), before, Map.of("activeVersionNo", header.versionNo()), null, null));
		idempotency.complete(actor.userId(), key, "POD_POLICY", policyId, 0L);
		return Outcome.fresh(config.findPolicy(policyId).orElseThrow());
	}

	@Transactional
	public PolicyRow unschedule(ActorContext actor, UUID policyId) {
		actor.requirePermission("config.rule.manage");
		VersionHeader header = config.findHeaderForUpdate(Kind.POD_POLICY, policyId)
				.orElseThrow(() -> BusinessException.notFound("POD policy"));
		requireScope(actor, header.zoneId());
		config.lockVersionActivation();
		if (!config.unschedule(Kind.POD_POLICY, policyId, clock.now())) {
			throw BusinessException.stateNotAllowed("Only a scheduled version can have its schedule cancelled.");
		}
		audit.append(AuditEntry.forUser(actor, AuditAction.POD_POLICY_UNSCHEDULED, "POD_POLICY", policyId,
				header.zoneId(), Map.of("status", "SCHEDULED"), Map.of("status", "DRAFT"), null, null));
		return config.findPolicy(policyId).orElseThrow();
	}

	private void requireScope(ActorContext actor, UUID zoneId) {
		if (zoneId == null) {
			return;
		}
		if (!config.zoneExists(zoneId)) {
			throw BusinessException.validation("zoneId", "UNKNOWN", "The zone does not exist.");
		}
		actor.requireScope(zoneId);
	}
}
