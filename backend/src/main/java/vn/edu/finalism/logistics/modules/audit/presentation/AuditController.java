package vn.edu.finalism.logistics.modules.audit.presentation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.edu.finalism.logistics.modules.audit.application.SearchAuditHandler;
import vn.edu.finalism.logistics.modules.audit.application.port.AuditQueryRepository.AuditRecordView;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.PageResult;

@RestController
@RequestMapping("/api/v1/audit-records")
class AuditController {

	private final SearchAuditHandler search;

	AuditController(SearchAuditHandler search) {
		this.search = search;
	}

	record AuditPage(List<Map<String, Object>> items, String nextCursor, Instant asOf) {
	}

	@GetMapping
	ResponseEntity<AuditPage> list(ActorContext actor, @RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to, @RequestParam(required = false) UUID actorId,
			@RequestParam(required = false) String action, @RequestParam(required = false) String entityType,
			@RequestParam(required = false) UUID entityId, @RequestParam(required = false) String correlationId,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		PageResult<AuditRecordView> page = search.search(actor, new SearchAuditHandler.Query(from, to, actorId, action,
				entityType, entityId, correlationId, cursor, limit));
		return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(new AuditPage(
				page.items().stream().map(AuditController::dto).toList(), page.nextCursor(), page.asOf()));
	}

	@GetMapping("/{auditId}")
	ResponseEntity<Map<String, Object>> get(ActorContext actor, @PathVariable UUID auditId) {
		return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(dto(search.get(actor, auditId)));
	}

	private static Map<String, Object> dto(AuditRecordView r) {
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("id", r.id());
		dto.put("occurredAt", r.occurredAt());
		Map<String, Object> actor = new LinkedHashMap<>();
		actor.put("id", r.actorId());
		actor.put("name", r.actorName());
		actor.put("type", r.actorType());
		dto.put("actor", actor);
		dto.put("action", r.action());
		dto.put("entityType", r.entityType());
		dto.put("entityId", r.entityId());
		dto.put("zoneId", r.zoneId());
		dto.put("before", r.before());
		dto.put("after", r.after());
		dto.put("reason", r.reason());
		dto.put("correlationId", r.correlationId());
		return dto;
	}
}
