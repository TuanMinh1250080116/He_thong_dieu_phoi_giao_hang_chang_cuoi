package vn.edu.finalism.logistics.modules.audit.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import vn.edu.finalism.logistics.shared.PageCursor;

public interface AuditQueryRepository {

	List<AuditRecordView> search(AuditFilter filter, int limit);

	Optional<AuditRecordView> findById(UUID id);

	record AuditFilter(Instant from, Instant to, UUID actorId, String action, String entityType, UUID entityId,
			String correlationId, Set<UUID> zoneIds, boolean includeNullZone, PageCursor cursor) {
	}

	record AuditRecordView(UUID id, Instant occurredAt, UUID actorId, String actorName, String actorType,
			String action, String entityType, UUID entityId, UUID zoneId, Map<String, Object> before,
			Map<String, Object> after, String reason, String correlationId) {
	}
}
