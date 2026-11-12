package vn.edu.finalism.logistics.modules.administration.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.administration.application.port.ConfigAdminRepository;
import vn.edu.finalism.logistics.modules.administration.application.port.ConfigAdminRepository.Kind;
import vn.edu.finalism.logistics.modules.administration.application.port.ConfigAdminRepository.RuleRow;
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
public class DispatchRuleAdminHandler {

	private final ConfigAdminRepository config;
	private final IdempotencyService idempotency;
	private final RequestFingerprinter fingerprinter;
	private final AuditWriter audit;
	private final ClockPort clock;

	DispatchRuleAdminHandler(ConfigAdminRepository config, IdempotencyService idempotency,
			RequestFingerprinter fingerprinter, AuditWriter audit, ClockPort clock) {
		this.config = config;
		this.idempotency = idempotency;
		this.fingerprinter = fingerprinter;
		this.audit = audit;
		this.clock = clock;
	}

	public record Draft(UUID zoneId, String name, BigDecimal weightDistance, BigDecimal weightCapacityFit,
			BigDecimal weightAvailability, BigDecimal weightZoneCompat, Integer ttlSeconds, Integer searchRadiusM,
			Integer maxLocationAgeSeconds, Integer persistTopN) {
	}

	@Transactional(readOnly = true)
	public List<RuleRow> list(ActorContext actor, String status) {
		actor.requirePermission("config.rule.manage");
		return config.listRules(actor.operationScopeIds(), status);
	}

	@Transactional
	public Outcome<RuleRow> create(ActorContext actor, IdempotencyKey key, Draft draft) {
		actor.requirePermission("config.rule.manage");
		requireScope(actor, draft.zoneId());
		ConfigValidation.validateRule(draft.name(), draft.weightDistance(), draft.weightCapacityFit(),
				draft.weightAvailability(), draft.weightZoneCompat(), draft.ttlSeconds(), draft.searchRadiusM(),
				draft.maxLocationAgeSeconds(), draft.persistTopN());
		byte[] fingerprint = fingerprinter.fingerprint("POST /admin/dispatch-rules", null, draft);
		IdempotencyClaim claim = idempotency.claim(actor.userId(), key, "CREATE_RULE", fingerprint);
		if (claim instanceof IdempotencyClaim.Replay replay) {
			return Outcome.replay(config.findRule(replay.result().resultRef())
					.orElseThrow(() -> BusinessException.notFound("Dispatch rule")));
		}

		Instant now = clock.now();
		UUID id = UUID.randomUUID();
		int versionNo = config.nextVersionNo(Kind.RULE, draft.zoneId());
		config.insertRule(new RuleRow(id, draft.zoneId(), versionNo, draft.name().trim(), "DRAFT",
				draft.weightDistance(), draft.weightCapacityFit(), draft.weightAvailability(), draft.weightZoneCompat(),
				draft.ttlSeconds(), draft.searchRadiusM(), draft.maxLocationAgeSeconds(), draft.persistTopN(), now,
				null, null, now), actor.userId(), now);
		audit.append(AuditEntry.forUser(actor, AuditAction.DISPATCH_RULE_CREATED, "DISPATCH_RULE", id, draft.zoneId(),
				null, Map.of("versionNo", versionNo, "status", "DRAFT"), null, null));
		idempotency.complete(actor.userId(), key, "DISPATCH_RULE", id, 0L);
		return Outcome.fresh(config.findRule(id).orElseThrow());
	}

	@Transactional
	public Outcome<RuleRow> activate(ActorContext actor, IdempotencyKey key, UUID ruleId, Instant effectiveFrom) {
		actor.requirePermission("config.rule.manage");
		byte[] fingerprint = fingerprinter.fingerprint("POST /admin/dispatch-rules/" + ruleId + "/activate", null,
				Map.of("effectiveFrom", effectiveFrom == null ? "" : effectiveFrom.toString()));
		IdempotencyClaim claim = idempotency.claim(actor.userId(), key, "ACTIVATE_RULE", fingerprint);
		if (claim instanceof IdempotencyClaim.Replay replay) {
			return Outcome.replay(config.findRule(replay.result().resultRef())
					.orElseThrow(() -> BusinessException.notFound("Dispatch rule")));
		}
		VersionHeader header = config.findHeaderForUpdate(Kind.RULE, ruleId)
				.orElseThrow(() -> BusinessException.notFound("Dispatch rule"));
		requireScope(actor, header.zoneId());
		if (!"DRAFT".equals(header.status())) {
			throw BusinessException.stateNotAllowed("Only a draft version can be activated.");
		}
		Instant now = clock.now();
		config.lockVersionActivation();
		if (effectiveFrom != null && effectiveFrom.isAfter(now)) {
			ScheduleRules.requireValid(effectiveFrom, now);
			if (config.scheduledExists(Kind.RULE, header.zoneId())) {
				throw BusinessException.stateNotAllowed(
						"Another version is already scheduled for this scope. Cancel its schedule first.");
			}
			config.schedule(Kind.RULE, ruleId, effectiveFrom);
			audit.append(AuditEntry.forUser(actor, AuditAction.DISPATCH_RULE_SCHEDULED, "DISPATCH_RULE", ruleId,
					header.zoneId(), null, Map.of("versionNo", header.versionNo(), "effectiveFrom", effectiveFrom.toString()),
					null, null));
			idempotency.complete(actor.userId(), key, "DISPATCH_RULE", ruleId, 0L);
			return Outcome.fresh(config.findRule(ruleId).orElseThrow());
		}
		Integer previous = config.retireActive(Kind.RULE, header.zoneId(), now).orElse(null);
		config.activate(Kind.RULE, ruleId, now);

		Map<String, Object> before = new LinkedHashMap<>();
		before.put("activeVersionNo", previous);
		audit.append(AuditEntry.forUser(actor, AuditAction.DISPATCH_RULE_CHANGED, "DISPATCH_RULE", ruleId,
				header.zoneId(), before, Map.of("activeVersionNo", header.versionNo()), null, null));
		idempotency.complete(actor.userId(), key, "DISPATCH_RULE", ruleId, 0L);
		return Outcome.fresh(config.findRule(ruleId).orElseThrow());
	}

	@Transactional
	public RuleRow unschedule(ActorContext actor, UUID ruleId) {
		actor.requirePermission("config.rule.manage");
		VersionHeader header = config.findHeaderForUpdate(Kind.RULE, ruleId)
				.orElseThrow(() -> BusinessException.notFound("Dispatch rule"));
		requireScope(actor, header.zoneId());
		config.lockVersionActivation();
		if (!config.unschedule(Kind.RULE, ruleId, clock.now())) {
			throw BusinessException.stateNotAllowed("Only a scheduled version can have its schedule cancelled.");
		}
		audit.append(AuditEntry.forUser(actor, AuditAction.DISPATCH_RULE_UNSCHEDULED, "DISPATCH_RULE", ruleId,
				header.zoneId(), Map.of("status", "SCHEDULED"), Map.of("status", "DRAFT"), null, null));
		return config.findRule(ruleId).orElseThrow();
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
