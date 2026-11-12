package vn.edu.finalism.logistics.modules.exception.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.audit.application.AuditAction;
import vn.edu.finalism.logistics.modules.audit.application.AuditEntry;
import vn.edu.finalism.logistics.modules.audit.application.AuditWriter;
import vn.edu.finalism.logistics.modules.delivery.application.DeliveryExceptionPort;
import vn.edu.finalism.logistics.modules.delivery.application.DeliveryExceptionPort.ExceptionTarget;
import vn.edu.finalism.logistics.modules.delivery.application.DeliveryExceptionPort.StopRef;
import vn.edu.finalism.logistics.modules.exception.application.port.ExceptionRepository;
import vn.edu.finalism.logistics.modules.exception.application.port.ExceptionRepository.ExceptionType;
import vn.edu.finalism.logistics.modules.exception.domain.DeliveryException;
import vn.edu.finalism.logistics.modules.identityaccess.application.UserDirectoryPort;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService;
import vn.edu.finalism.logistics.modules.order.application.OrderTrackingService;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService.OrderView;
import vn.edu.finalism.logistics.modules.proof.application.ExceptionEvidencePort;
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
public class ReportExceptionHandler {

	private static final List<String> REPORTABLE = List.of("ASSIGNED", "ACCEPTED", "IN_PROGRESS");

	private final ExceptionRepository exceptions;
	private final DeliveryExceptionPort deliveryPort;
	private final ExceptionEvidencePort evidencePort;
	private final OrderLifecycleService orders;
	private final OrderTrackingService tracking;
	private final UserDirectoryPort directory;
	private final ExceptionAccess access;
	private final IdempotencyService idempotency;
	private final RequestFingerprinter fingerprinter;
	private final AuditWriter audit;
	private final EventPublicationPort events;
	private final ClockPort clock;

	ReportExceptionHandler(ExceptionRepository exceptions, DeliveryExceptionPort deliveryPort,
			ExceptionEvidencePort evidencePort, OrderLifecycleService orders, OrderTrackingService tracking,
			UserDirectoryPort directory,
			ExceptionAccess access, IdempotencyService idempotency, RequestFingerprinter fingerprinter,
			AuditWriter audit, EventPublicationPort events, ClockPort clock) {
		this.exceptions = exceptions;
		this.deliveryPort = deliveryPort;
		this.evidencePort = evidencePort;
		this.orders = orders;
		this.tracking = tracking;
		this.directory = directory;
		this.access = access;
		this.idempotency = idempotency;
		this.fingerprinter = fingerprinter;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	public record ReportCommand(String type, String description, UUID stopId, UUID orderId, String severity,
			List<UUID> evidenceIds) {
	}

	@Transactional
	public Outcome<ExceptionDetail> handle(ActorContext actor, UUID deliveryId, IdempotencyKey key,
			ReportCommand command) {
		actor.requirePermission("exception.report");
		if (command.type() == null || command.type().isBlank()) {
			throw BusinessException.validation("type", "REQUIRED", "The exception type is required.");
		}
		if (command.description() == null || command.description().isBlank()) {
			throw BusinessException.validation("description", "REQUIRED", "A description is required.");
		}
		ExceptionType type = exceptions.findActiveType(command.type())
				.orElseThrow(() -> BusinessException.validation("type", "UNKNOWN", "Unknown exception type."));
		List<UUID> evidenceIds = command.evidenceIds() == null ? List.of() : command.evidenceIds();

		byte[] fingerprint = fingerprinter.fingerprint("POST /deliveries/" + deliveryId + "/exceptions", null, command);
		IdempotencyClaim claim = idempotency.claim(actor.userId(), key, "REPORT_EXCEPTION", fingerprint);
		if (claim instanceof IdempotencyClaim.Replay replay) {
			return Outcome.replay(detail(actor, replay.result().resultRef()));
		}

		ExceptionTarget target = deliveryPort.lockForException(deliveryId);
		if (target.stops().isEmpty()) {
			throw BusinessException.notFound("Delivery");
		}
		OrderView firstOrder = orders.get(List.of(target.stops().get(0).orderId())).get(0);
		UUID zoneId = firstOrder.zoneId();
		boolean owner = access.driverIdOf(actor) != null && access.driverIdOf(actor).equals(target.assignedDriverId());
		boolean inScope = zoneId != null && actor.hasScope(zoneId);
		if (!owner && !inScope) {
			throw BusinessException.notFound("Delivery");
		}
		if (!REPORTABLE.contains(target.status())) {
			throw BusinessException.stateNotAllowed("Problems can only be reported on a delivery that is still active.");
		}

		StopRef stop = null;
		UUID orderId = command.orderId();
		if (command.stopId() != null) {
			stop = target.stops().stream().filter(s -> s.id().equals(command.stopId())).findFirst()
					.orElseThrow(() -> BusinessException.notFound("Stop"));
			if (orderId != null && !orderId.equals(stop.orderId())) {
				throw BusinessException.validation("orderId", "MISMATCH", "The order does not belong to that stop.");
			}
			orderId = stop.orderId();
		}
		else if (orderId != null) {
			UUID wanted = orderId;
			if (target.stops().stream().noneMatch(s -> s.orderId().equals(wanted))) {
				throw BusinessException.notFound("Order");
			}
		}

		DeliveryException draft = DeliveryException.report(UUID.randomUUID(), exceptions.nextCode(), deliveryId,
				stop == null ? null : stop.id(), orderId, type.code(), severityFor(actor, type, command.severity()),
				type.impact(), command.description(), actor.userId(), clock.now().truncatedTo(ChronoUnit.MILLIS));
		if ("STOP".equals(draft.blockingScope()) && stop == null) {
			throw BusinessException.validation("stopId", "REQUIRED",
					"This type of problem blocks a stop, so the stop must be given.");
		}

		Instant now = draft.reportedAt();
		exceptions.insert(draft, now);
		for (UUID attached : evidencePort.attachForException(evidenceIds, actor.userId(), deliveryId)) {
			exceptions.insertEvidence(draft.id(), attached);
		}
		if (draft.blocking()) {
			deliveryPort.registerBlocking(draft.id(), deliveryId, stop == null ? null : stop.id(),
					draft.blockingScope(), actor.userId());
		}

		Map<String, Object> after = new LinkedHashMap<>();
		after.put("code", draft.code());
		after.put("type", type.code());
		after.put("severity", draft.severity());
		after.put("impact", draft.impact());
		after.put("deliveryId", deliveryId.toString());
		audit.append(AuditEntry.forUser(actor, AuditAction.EXCEPTION_REPORTED, "EXCEPTION", draft.id(), zoneId, null,
				after, null, null));

		List<String> staff = recipients(zoneId, actor.userId());
		if (!staff.isEmpty()) {
			events.append(new EventEnvelope(UUID.randomUUID(), "DeliveryExceptionReported", 1, "DeliveryException",
					draft.id(), now, actor.userId(), actor.correlationId(),
					Map.of("exceptionId", draft.id().toString(), "exceptionCode", draft.code(),
							"deliveryCode", target.code(), "typeName", type.name(), "recipientUserIds", staff)));
		}
		if ("CUSTOMER_IMPACTING".equals(draft.impact())) {
			List<UUID> affected = draft.orderId() != null ? List.of(draft.orderId())
					: target.stops().stream().map(StopRef::orderId).distinct().toList();
			affected.forEach(id -> tracking.record(id, OrderTrackingService.Milestone.DELIVERY_ISSUE, now));
			List<String> customers = orders.get(affected).stream().map(o -> o.customerId().toString()).distinct()
					.toList();
			events.append(new EventEnvelope(UUID.randomUUID(), "OrderAffectedByException", 1, "DeliveryException",
					draft.id(), now, actor.userId(), actor.correlationId(),
					Map.of("exceptionId", draft.id().toString(), "deliveryCode", target.code(),
							"recipientUserIds", customers)));
		}

		idempotency.complete(actor.userId(), key, "EXCEPTION", draft.id(), 0L);
		return Outcome.fresh(detail(actor, draft.id()));
	}

	private String severityFor(ActorContext actor, ExceptionType type, String requested) {
		if (requested == null || requested.isBlank() || requested.equals(type.severity())) {
			return type.severity();
		}
		if (!actor.hasPermission("exception.manage")) {
			throw BusinessException.validation("severity", "NOT_ALLOWED",
					"Only an operations manager can change the severity of a report.");
		}
		return requested;
	}

	private List<String> recipients(UUID zoneId, UUID except) {
		if (zoneId == null) {
			return List.of();
		}
		return directory.userIdsWithPermissionInZone("exception.view", zoneId).stream()
				.filter(id -> !id.equals(except)).map(UUID::toString).toList();
	}

	private ExceptionDetail detail(ActorContext actor, UUID id) {
		var view = exceptions.findView(id).orElseThrow(() -> BusinessException.notFound("Exception"));
		return new ExceptionDetail(view, exceptions.notes(id), exceptions.evidenceIds(id),
				access.isAssignedDriver(actor, view));
	}
}
