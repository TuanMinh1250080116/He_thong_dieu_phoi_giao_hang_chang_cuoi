package vn.edu.finalism.logistics.modules.exception.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.audit.application.AuditAction;
import vn.edu.finalism.logistics.modules.audit.application.AuditEntry;
import vn.edu.finalism.logistics.modules.audit.application.AuditWriter;
import vn.edu.finalism.logistics.modules.delivery.application.DeliveryExceptionPort;
import vn.edu.finalism.logistics.modules.exception.application.port.ExceptionRepository;
import vn.edu.finalism.logistics.modules.exception.application.port.ExceptionRepository.ExceptionView;
import vn.edu.finalism.logistics.modules.exception.domain.DeliveryException;
import vn.edu.finalism.logistics.modules.identityaccess.application.UserDirectoryPort;
import vn.edu.finalism.logistics.platform.idempotency.IdempotencyClaim;
import vn.edu.finalism.logistics.platform.idempotency.IdempotencyService;
import vn.edu.finalism.logistics.platform.idempotency.RequestFingerprinter;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.IdempotencyKey;
import vn.edu.finalism.logistics.shared.Outcome;
import vn.edu.finalism.logistics.shared.event.EventEnvelope;
import vn.edu.finalism.logistics.shared.event.EventPublicationPort;

@Service
public class ManageExceptionHandler {

	private final ExceptionRepository exceptions;
	private final DeliveryExceptionPort deliveryPort;
	private final UserDirectoryPort directory;
	private final ExceptionAccess access;
	private final IdempotencyService idempotency;
	private final RequestFingerprinter fingerprinter;
	private final AuditWriter audit;
	private final EventPublicationPort events;
	private final ClockPort clock;

	ManageExceptionHandler(ExceptionRepository exceptions, DeliveryExceptionPort deliveryPort,
			UserDirectoryPort directory, ExceptionAccess access, IdempotencyService idempotency,
			RequestFingerprinter fingerprinter, AuditWriter audit, EventPublicationPort events, ClockPort clock) {
		this.exceptions = exceptions;
		this.deliveryPort = deliveryPort;
		this.directory = directory;
		this.access = access;
		this.idempotency = idempotency;
		this.fingerprinter = fingerprinter;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	public ExceptionDetail investigate(ActorContext actor, UUID exceptionId, long expectedVersion, UUID assigneeId,
			String note) {
		DeliveryException current = lock(actor, exceptionId, expectedVersion);
		ExceptionView view = exceptions.findView(exceptionId).orElseThrow();
		actor.requireScope(view.zoneId());
		if (assigneeId == null) {
			throw BusinessException.validation("assigneeId", "REQUIRED", "An assignee is required.");
		}
		if (note == null || note.isBlank()) {
			throw BusinessException.validation("note", "REQUIRED", "An investigation note is required.");
		}
		if (!directory.userHasPermission(assigneeId, "exception.manage")) {
			throw BusinessException.validation("assigneeId", "NOT_ELIGIBLE",
					"The assignee must be an operations manager.");
		}

		Instant now = clock.now();
		DeliveryException next = current.investigate(assigneeId, now);
		save(next, expectedVersion, now);
		exceptions.insertNote(exceptionId, actor.userId(), "INVESTIGATION", note.trim(), now);

		audit(actor, AuditAction.EXCEPTION_INVESTIGATING, view, current, next, Map.of("assigneeId", assigneeId.toString()));
		notify("DeliveryExceptionInvestigating", actor, view, now);
		return detail(actor, exceptionId);
	}

	@Transactional
	public Outcome<ExceptionDetail> resolve(ActorContext actor, UUID exceptionId, IdempotencyKey key,
			long expectedVersion, String outcome, String note) {
		actor.requirePermission("exception.manage");
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("outcome", outcome == null ? "" : outcome);
		body.put("note", note == null ? "" : note);
		byte[] fingerprint = fingerprinter.fingerprint("POST /exceptions/" + exceptionId + "/resolve",
				String.valueOf(expectedVersion), body);
		IdempotencyClaim claim = idempotency.claim(actor.userId(), key, "RESOLVE_EXCEPTION", fingerprint);
		if (claim instanceof IdempotencyClaim.Replay replay) {
			return Outcome.replay(detail(actor, replay.result().resultRef()));
		}

		DeliveryException current = lock(actor, exceptionId, expectedVersion);
		ExceptionView view = exceptions.findView(exceptionId).orElseThrow();
		actor.requireScope(view.zoneId());

		Instant now = clock.now();
		DeliveryException next = current.resolve(outcome, note, actor.userId(), now);
		save(next, expectedVersion, now);
		exceptions.insertNote(exceptionId, actor.userId(), "RESOLUTION", note.trim(), now);
		if (current.blocking()) {
			deliveryPort.clearBlocking(exceptionId);
		}

		audit(actor, AuditAction.EXCEPTION_RESOLVED, view, current, next, Map.of("outcome", outcome));
		notify("DeliveryExceptionResolved", actor, view, now);
		idempotency.complete(actor.userId(), key, "EXCEPTION", exceptionId, expectedVersion + 1);
		return Outcome.fresh(detail(actor, exceptionId));
	}

	@Transactional
	public Outcome<ExceptionDetail> cancel(ActorContext actor, UUID exceptionId, IdempotencyKey key,
			long expectedVersion, String reason) {
		actor.requirePermission("exception.manage");
		byte[] fingerprint = fingerprinter.fingerprint("POST /exceptions/" + exceptionId + "/cancel",
				String.valueOf(expectedVersion), Map.of("reason", reason == null ? "" : reason));
		IdempotencyClaim claim = idempotency.claim(actor.userId(), key, "CANCEL_EXCEPTION", fingerprint);
		if (claim instanceof IdempotencyClaim.Replay replay) {
			return Outcome.replay(detail(actor, replay.result().resultRef()));
		}

		DeliveryException current = lock(actor, exceptionId, expectedVersion);
		ExceptionView view = exceptions.findView(exceptionId).orElseThrow();
		actor.requireScope(view.zoneId());

		Instant now = clock.now();
		DeliveryException next = current.cancel(reason, actor.userId(), now);
		save(next, expectedVersion, now);
		if (current.blocking()) {
			deliveryPort.clearBlocking(exceptionId);
		}

		audit(actor, AuditAction.EXCEPTION_CANCELLED, view, current, next, Map.of("reason", reason.trim()));
		notify("DeliveryExceptionCancelled", actor, view, now);
		idempotency.complete(actor.userId(), key, "EXCEPTION", exceptionId, expectedVersion + 1);
		return Outcome.fresh(detail(actor, exceptionId));
	}

	private DeliveryException lock(ActorContext actor, UUID exceptionId, long expectedVersion) {
		actor.requirePermission("exception.manage");
		DeliveryException current = exceptions.findByIdForUpdate(exceptionId)
				.orElseThrow(() -> BusinessException.notFound("Exception"));
		if (current.version() != expectedVersion) {
			throw BusinessException.versionConflict(current.version());
		}
		return current;
	}

	private void save(DeliveryException next, long expectedVersion, Instant now) {
		if (!exceptions.update(next, expectedVersion, now)) {
			throw BusinessException.versionConflict(expectedVersion + 1);
		}
	}

	private void audit(ActorContext actor, AuditAction action, ExceptionView view, DeliveryException before,
			DeliveryException after, Map<String, Object> extra) {
		Map<String, Object> afterState = new LinkedHashMap<>(extra);
		afterState.put("status", after.status().name());
		audit.append(AuditEntry.forUser(actor, action, "EXCEPTION", before.id(), view.zoneId(),
				Map.of("status", before.status().name()), afterState, null, null));
	}

	private void notify(String eventType, ActorContext actor, ExceptionView view, Instant now) {
		List<String> recipients = new java.util.ArrayList<>(
				directory.userIdsWithPermissionInZone("exception.view", view.zoneId()).stream().map(UUID::toString).toList());
		String reporter = view.exception().reporterUserId().toString();
		if (!recipients.contains(reporter)) {
			recipients.add(reporter);
		}
		recipients.remove(actor.userId().toString());
		if (recipients.isEmpty()) {
			return;
		}
		events.append(new EventEnvelope(UUID.randomUUID(), eventType, 1, "DeliveryException", view.exception().id(), now,
				actor.userId(), actor.correlationId(),
				Map.of("exceptionId", view.exception().id().toString(), "exceptionCode", view.exception().code(),
						"recipientUserIds", recipients)));
	}

	private ExceptionDetail detail(ActorContext actor, UUID id) {
		ExceptionView view = exceptions.findView(id).orElseThrow(() -> BusinessException.notFound("Exception"));
		return new ExceptionDetail(view, exceptions.notes(id), exceptions.evidenceIds(id),
				access.isAssignedDriver(actor, view));
	}
}
