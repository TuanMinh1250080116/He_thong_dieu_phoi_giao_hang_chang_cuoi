package vn.edu.finalism.logistics.modules.delivery.presentation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import vn.edu.finalism.logistics.modules.delivery.application.CompleteDeliveryHandler;
import vn.edu.finalism.logistics.modules.delivery.application.DeliveryQueryHandler;
import vn.edu.finalism.logistics.modules.delivery.application.StartDeliveryHandler;
import vn.edu.finalism.logistics.modules.delivery.application.UpdateStopHandler;
import vn.edu.finalism.logistics.modules.delivery.domain.Delivery;
import vn.edu.finalism.logistics.modules.delivery.domain.DeliveryStatus;
import vn.edu.finalism.logistics.modules.delivery.domain.DeliveryStop;
import vn.edu.finalism.logistics.modules.delivery.domain.StopAction;
import vn.edu.finalism.logistics.modules.delivery.domain.StopStatus;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.IdempotencyKey;
import vn.edu.finalism.logistics.shared.IfMatch;
import vn.edu.finalism.logistics.shared.Outcome;

@RestController
@RequestMapping("/api/v1/deliveries")
class DeliveryController {

	private final StartDeliveryHandler start;
	private final UpdateStopHandler updateStop;
	private final CompleteDeliveryHandler complete;
	private final DeliveryQueryHandler query;

	DeliveryController(StartDeliveryHandler start, UpdateStopHandler updateStop, CompleteDeliveryHandler complete,
			DeliveryQueryHandler query) {
		this.start = start;
		this.updateStop = updateStop;
		this.complete = complete;
		this.query = query;
	}

	record StopActionRequest(@NotNull StopAction action) {
	}

	record StopDto(UUID id, UUID orderId, int sequence, StopStatus status, String address, Instant arrivedAt) {
	}

	record BlockedByDto(String capability, String reason, List<String> refs) {
	}

	record DeliveryDto(UUID id, UUID batchId, DeliveryStatus status, UUID driverId, List<StopDto> stops,
			int completedStops, long version, List<String> capabilities, List<BlockedByDto> blockedBy) {
	}

	record DeliveryEnvelope(DeliveryDto data, long version, List<String> capabilities) {
	}

	@GetMapping("/{deliveryId}")
	ResponseEntity<DeliveryDto> get(ActorContext actor, @PathVariable UUID deliveryId) {
		Delivery delivery = query.get(actor, deliveryId);
		return ResponseEntity.ok().header(HttpHeaders.ETAG, IfMatch.etag(delivery.version())).body(toDto(delivery));
	}

	@PostMapping("/{deliveryId}/start")
	ResponseEntity<DeliveryEnvelope> start(ActorContext actor, @PathVariable UUID deliveryId,
			@RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
		return respond(start.handle(actor, deliveryId, IdempotencyKey.fromHeader(idempotencyKey),
				IfMatch.parse(ifMatch)));
	}

	@PostMapping("/{deliveryId}/stops/{stopId}/actions")
	ResponseEntity<DeliveryEnvelope> stopAction(ActorContext actor, @PathVariable UUID deliveryId,
			@PathVariable UUID stopId,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@Valid @RequestBody StopActionRequest request) {
		return respond(Outcome.fresh(updateStop.handle(actor, deliveryId, stopId, request.action(),
				IfMatch.parse(ifMatch))));
	}

	@PostMapping("/{deliveryId}/complete")
	ResponseEntity<DeliveryEnvelope> complete(ActorContext actor, @PathVariable UUID deliveryId,
			@RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
		return respond(complete.handle(actor, deliveryId, IdempotencyKey.fromHeader(idempotencyKey),
				IfMatch.parse(ifMatch)));
	}

	private static ResponseEntity<DeliveryEnvelope> respond(Outcome<Delivery> outcome) {
		Delivery delivery = outcome.value();
		DeliveryDto dto = toDto(delivery);
		ResponseEntity.BodyBuilder response = ResponseEntity.ok();
		if (outcome.replayed()) {
			response.header("Idempotent-Replayed", "true");
		}
		return response.header(HttpHeaders.ETAG, IfMatch.etag(delivery.version()))
				.body(new DeliveryEnvelope(dto, delivery.version(), dto.capabilities()));
	}

	private static DeliveryDto toDto(Delivery d) {
		List<StopDto> stops = d.stops().stream()
				.map(s -> new StopDto(s.id(), s.orderId(), s.sequenceNo(), s.status(), s.addressText(), s.arrivedAt()))
				.toList();
		List<String> capabilities = new ArrayList<>();
		List<BlockedByDto> blockedBy = new ArrayList<>();
		if (d.status() == DeliveryStatus.ACCEPTED) {
			capabilities.add("canStartDelivery");
		}
		if (d.status() == DeliveryStatus.IN_PROGRESS) {
			List<DeliveryStop> open = d.stops().stream().filter(s -> s.status() != StopStatus.COMPLETED).toList();
			if (!open.isEmpty()) {
				capabilities.add("canSubmitPod");
				blockedBy.add(new BlockedByDto("canCompleteDelivery", "INCOMPLETE_STOPS",
						open.stream().map(s -> s.id().toString()).toList()));
			}
			else {
				capabilities.add("canCompleteDelivery");
			}
		}
		return new DeliveryDto(d.id(), d.batchId(), d.status(), d.assignedDriverId(), stops, d.completedStops(),
				d.version(), capabilities, blockedBy);
	}
}
