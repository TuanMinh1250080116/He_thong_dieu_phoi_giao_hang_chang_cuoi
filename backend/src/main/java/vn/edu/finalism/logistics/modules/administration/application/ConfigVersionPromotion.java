package vn.edu.finalism.logistics.modules.administration.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.administration.application.port.ConfigAdminRepository;
import vn.edu.finalism.logistics.modules.administration.application.port.ConfigAdminRepository.DuePromotion;
import vn.edu.finalism.logistics.modules.administration.application.port.ConfigAdminRepository.Kind;
import vn.edu.finalism.logistics.modules.audit.application.AuditAction;
import vn.edu.finalism.logistics.modules.audit.application.AuditActorType;
import vn.edu.finalism.logistics.modules.audit.application.AuditEntry;
import vn.edu.finalism.logistics.modules.audit.application.AuditWriter;
import vn.edu.finalism.logistics.shared.ClockPort;

@Service
public class ConfigVersionPromotion {

	private final ConfigAdminRepository config;
	private final AuditWriter audit;
	private final ClockPort clock;

	ConfigVersionPromotion(ConfigAdminRepository config, AuditWriter audit, ClockPort clock) {
		this.config = config;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional
	public int promoteDue() {
		Instant now = clock.now();
		config.lockVersionActivation();
		int total = 0;
		for (Kind kind : Kind.values()) {
			for (DuePromotion item : config.promoteDue(kind, now)) {
				Map<String, Object> before = new LinkedHashMap<>();
				before.put("activeVersionNo", item.previousVersionNo());
				boolean rule = kind == Kind.RULE;
				audit.append(new AuditEntry(rule ? AuditAction.DISPATCH_RULE_CHANGED : AuditAction.POD_POLICY_CHANGED,
						AuditActorType.SYSTEM, null, rule ? "DISPATCH_RULE" : "POD_POLICY", item.id(), item.zoneId(),
						before, Map.of("activeVersionNo", item.versionNo(), "scheduled", true),
						"Scheduled activation", "cfg-promotion-" + UUID.randomUUID(), null));
				total++;
			}
		}
		return total;
	}
}
