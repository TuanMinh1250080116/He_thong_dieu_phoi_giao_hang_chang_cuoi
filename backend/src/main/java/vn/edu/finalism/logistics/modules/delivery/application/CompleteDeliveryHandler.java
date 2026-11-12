package vn.edu.finalism.logistics.modules.delivery.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.audit.application.AuditAction;
import vn.edu.finalism.logistics.modules.audit.application.AuditEntry;
import vn.edu.finalism.logistics.modules.audit.application.AuditWriter;
import vn.edu.finalism.logistics.modules.delivery.application.port.DeliveryRepository;
import vn.edu.finalism.logistics.modules.delivery.domain.CompletionCheck;
import vn.edu.finalism.logistics.modules.delivery.domain.Delivery;
import vn.edu.finalism.logistics.modules.delivery.domain.DeliveryStop;
import vn.edu.finalism.logistics.modules.dispatch.application.DispatchCompletionService;
import vn.edu.finalism.logistics.modules.driverfleet.application.DriverAvailabilityService;
import vn.edu.finalism.logistics.modules.driverfleet.application.DriverAvailabilityService.DriverSummary;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService.OrderView;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService.Transition;
import vn.edu.finalism.logistics.modules.proof.application.PodQueryPort;
import vn.edu.finalism.logistics.platform.idempotency.IdempotencyClaim;
import vn.edu.finalism.logistics.platform.idempotency.IdempotencyService;
import vn.edu.finalism.logistics.platform.idempotency.RequestFingerprinter;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.ErrorCode;
import vn.edu.finalism.logistics.shared.IdempotencyKey;
import vn.edu.finalism.logistics.shared.Outcome;
import vn.edu.finalism.logistics.shared.event.EventEnvelope;
import vn.edu.finalism.logistics.shared.event.EventPublicationPort;

@Service
public class CompleteDeliveryHandler {

	private final DeliveryRepository deliveries;
	private final DeliveryAccess access;
	private final DriverAvailabilityService drivers;
	private final OrderLifecycleService orderLifecycle;
	private final PodQueryPort pods;
	private final DispatchCompletionService dispatchCompletion;
	private final IdempotencyService idempotency;
	private final RequestFingerprinter fingerprinter;
	private final AuditWriter audit;
	private final EventPublicationPort events;
	private final ClockPort clock;

	CompleteDeliveryHandler(DeliveryRepository deliveries, DeliveryAccess access, DriverAvailabilityService drivers,
			OrderLifecycleService orderLifecycle, PodQueryPort pods, DispatchCompletionService dispatchCompletion,
			IdempotencyService idempotency, RequestFingerprinter fingerprinter, AuditWriter audit,
			EventPublicationPort events, ClockPort clock) {
		this.deliveries = deliveries;
		this.access = access;
		this.drivers = drivers;
		this.orderLifecycle = orderLifecycle;
		this.pods = pods;
		this.dispatchCompletion = dispatchCompletion;
		this.idempotency = idempotency;
		this.fingerprinter = fingerprinter;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	public Outcome<Delivery> handle(ActorContext actor, UUID deliveryId, IdempotencyKey key, long expectedVersion) {
		DriverSummary driver = access.requireDriver(actor);
		byte[] fingerprint = fingerprinter.fingerprint("POST /deliveries/" + deliveryId + "/complete",
				String.valueOf(expectedVersion), null);
		IdempotencyClaim claim = idempotency.claim(actor.userId(), key, "COMPLETE_DELIVERY", fingerprint);
		if (claim instanceof IdempotencyClaim.Replay replay) {
			return Outcome.replay(deliveries.findById(replay.result().resultRef())
					.orElseThrow(() -> BusinessException.notFound("Delivery")));
		}

		Delivery delivery = access.requireOwnedBy(driver, deliveries.findByIdForUpdate(deliveryId)
				.orElseThrow(() -> BusinessException.notFound("Delivery")));
		DeliveryAccess.requireVersion(delivery, expectedVersion);

		Set<UUID> stopsWithPod = pods.stopIdsWithPod(delivery.stops().stream().map(DeliveryStop::id).toList());
		CompletionCheck check = delivery.completionCheck(stopsWithPod, deliveries.openBlockingExceptionIds(deliveryId));
		if (!check.allowed()) {
			throw new BusinessException(ErrorCode.COMPLETION_PREREQUISITES_MISSING,
					"The delivery still has open prerequisites.",
					Map.of("incompleteStopIds", ids(check.incompleteStopIds()),
							"missingPodStopIds", ids(check.missingPodStopIds()),
							"blockingExceptionIds", ids(check.blockingExceptionIds())));
		}

		Instant now = clock.now();
		Delivery completed = delivery.complete(now, check);
		if (!deliveries.update(completed, expectedVersion, now)) {
			throw BusinessException.versionConflict(expectedVersion + 1);
		}
		List<UUID> orderIds = delivery.stops().stream().map(DeliveryStop::orderId).distinct().toList();
		List<OrderView> orders = orderLifecycle.lockAndGet(orderIds);
		orderLifecycle.apply(orderIds, Transition.MARK_DELIVERED);
		dispatchCompletion.onDeliveryCompleted(delivery.batchId(), delivery.decisionId(), now);
		drivers.release(driver.id());

		audit.append(AuditEntry.forUser(actor, AuditAction.DELIVERY_COMPLETED, "DELIVERY", deliveryId,
				orders.get(0).zoneId(), Map.of("status", "IN_PROGRESS"),
				Map.of("status", "COMPLETED", "stopCount", delivery.stops().size()), null, null));
		events.append(new EventEnvelope(UUID.randomUUID(), "DeliveryCompleted", 1, "Delivery", deliveryId, now,
				actor.userId(), actor.correlationId(),
				Map.of("deliveryId", deliveryId.toString(), "deliveryCode", delivery.code(),
						"recipientUserIds", orders.stream().map(o -> o.customerId().toString()).distinct().toList())));
		idempotency.complete(actor.userId(), key, "DELIVERY", deliveryId, expectedVersion + 1);
		return Outcome.fresh(deliveries.findById(deliveryId).orElseThrow());
	}

	private static List<String> ids(List<UUID> values) {
		return values.stream().map(UUID::toString).toList();
	}
}
