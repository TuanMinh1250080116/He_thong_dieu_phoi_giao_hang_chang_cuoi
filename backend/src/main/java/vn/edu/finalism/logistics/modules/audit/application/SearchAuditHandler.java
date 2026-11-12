package vn.edu.finalism.logistics.modules.audit.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.audit.application.port.AuditQueryRepository;
import vn.edu.finalism.logistics.modules.audit.application.port.AuditQueryRepository.AuditFilter;
import vn.edu.finalism.logistics.modules.audit.application.port.AuditQueryRepository.AuditRecordView;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.PageCursor;
import vn.edu.finalism.logistics.shared.PageResult;

@Service
public class SearchAuditHandler {

	static final Duration MAX_RANGE = Duration.ofDays(92);

	private final AuditQueryRepository audits;
	private final ClockPort clock;

	SearchAuditHandler(AuditQueryRepository audits, ClockPort clock) {
		this.audits = audits;
		this.clock = clock;
	}

	public record Query(Instant from, Instant to, UUID actorId, String action, String entityType, UUID entityId,
			String correlationId, String cursor, Integer limit) {
	}

	@Transactional(readOnly = true)
	public PageResult<AuditRecordView> search(ActorContext actor, Query query) {
		actor.requirePermission("audit.view");
		if (query.from() == null) {
			throw BusinessException.validation("from", "REQUIRED", "from is required.");
		}
		if (query.to() == null) {
			throw BusinessException.validation("to", "REQUIRED", "to is required.");
		}
		if (!query.to().isAfter(query.from())) {
			throw BusinessException.validation("to", "BEFORE_FROM", "to must be after from.");
		}
		if (Duration.between(query.from(), query.to()).compareTo(MAX_RANGE) > 0) {
			throw BusinessException.validation("to", "RANGE_TOO_LARGE", "The time range must not exceed 92 days.");
		}
		int limit = PageCursor.normalizeLimit(query.limit());
		AuditFilter filter = new AuditFilter(query.from(), query.to(), query.actorId(), blankToNull(query.action()),
				blankToNull(query.entityType()), query.entityId(), blankToNull(query.correlationId()),
				actor.operationScopeIds(), actor.roles().contains("SYSTEM_ADMIN"),
				query.cursor() == null || query.cursor().isBlank() ? null : PageCursor.decode(query.cursor()));
		List<AuditRecordView> rows = audits.search(filter, limit + 1);
		boolean more = rows.size() > limit;
		List<AuditRecordView> page = more ? rows.subList(0, limit) : rows;
		String next = more ? new PageCursor(page.get(page.size() - 1).occurredAt(), page.get(page.size() - 1).id())
				.encode() : null;
		return new PageResult<>(List.copyOf(page), next, clock.now());
	}

	@Transactional(readOnly = true)
	public AuditRecordView get(ActorContext actor, UUID id) {
		actor.requirePermission("audit.view");
		AuditRecordView record = audits.findById(id).orElseThrow(() -> BusinessException.notFound("Audit record"));
		boolean visible = record.zoneId() == null ? actor.roles().contains("SYSTEM_ADMIN")
				: actor.hasScope(record.zoneId());
		if (!visible) {
			throw BusinessException.notFound("Audit record");
		}
		return record;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
