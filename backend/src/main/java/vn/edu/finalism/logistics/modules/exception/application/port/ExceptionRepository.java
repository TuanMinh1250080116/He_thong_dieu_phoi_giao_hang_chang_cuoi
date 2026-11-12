package vn.edu.finalism.logistics.modules.exception.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import vn.edu.finalism.logistics.modules.exception.domain.DeliveryException;
import vn.edu.finalism.logistics.shared.PageCursor;

public interface ExceptionRepository {

	String nextCode();

	void insert(DeliveryException exception, Instant now);

	Optional<DeliveryException> findByIdForUpdate(UUID id);

	boolean update(DeliveryException exception, long expectedVersion, Instant now);

	void insertNote(UUID exceptionId, UUID authorId, String noteType, String body, Instant now);

	void insertEvidence(UUID exceptionId, UUID evidenceId);

	Optional<ExceptionView> findView(UUID id);

	List<ExceptionView> queue(QueueFilter filter, int limit);

	List<NoteView> notes(UUID exceptionId);

	List<UUID> evidenceIds(UUID exceptionId);

	Optional<ExceptionType> findActiveType(String code);

	List<ExceptionType> activeTypes();

	record ExceptionType(String code, String name, String severity, String impact) {
	}

	record NoteView(UUID id, UUID authorId, String authorName, String type, String body, Instant createdAt) {
	}

	record ExceptionView(DeliveryException exception, UUID zoneId, String deliveryCode, UUID assignedDriverId,
			String typeName, String reporterName, String assigneeName, String stopStatus) {
	}

	record QueueFilter(Set<UUID> zoneIds, UUID driverId, String status, String severity, String type, UUID assigneeId,
			Boolean blocking, UUID deliveryId, Instant from, Instant to, PageCursor cursor) {
	}
}
