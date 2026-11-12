package vn.edu.finalism.logistics.modules.order.presentation;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import vn.edu.finalism.logistics.modules.order.application.CancelOrderHandler;
import vn.edu.finalism.logistics.modules.order.application.CreateOrderCommand;
import vn.edu.finalism.logistics.modules.order.application.UpdateOrderHandler;
import vn.edu.finalism.logistics.modules.order.application.CreateOrderCommand.SaveMode;
import vn.edu.finalism.logistics.modules.order.application.CreateOrderHandler;
import vn.edu.finalism.logistics.modules.order.application.OrderQueryHandler;
import vn.edu.finalism.logistics.modules.order.application.SubmitOrderHandler;
import vn.edu.finalism.logistics.modules.order.domain.Order;
import vn.edu.finalism.logistics.modules.order.domain.OrderStatus;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.GeoPoint;
import vn.edu.finalism.logistics.shared.IdempotencyKey;
import vn.edu.finalism.logistics.shared.IfMatch;
import vn.edu.finalism.logistics.shared.Outcome;
import vn.edu.finalism.logistics.shared.PageResult;

@RestController
@RequestMapping("/api/v1/orders")
class OrderController {

	private final CreateOrderHandler createHandler;
	private final SubmitOrderHandler submitHandler;
	private final OrderQueryHandler queryHandler;
	private final UpdateOrderHandler updateHandler;
	private final CancelOrderHandler cancelHandler;

	OrderController(CreateOrderHandler createHandler, SubmitOrderHandler submitHandler,
			OrderQueryHandler queryHandler, UpdateOrderHandler updateHandler, CancelOrderHandler cancelHandler) {
		this.updateHandler = updateHandler;
		this.cancelHandler = cancelHandler;
		this.createHandler = createHandler;
		this.submitHandler = submitHandler;
		this.queryHandler = queryHandler;
	}

	record PointDto(double lat, double lng) {
		GeoPoint toDomain() {
			return new GeoPoint(lat, lng);
		}
	}

	record AddressDto(@NotNull String address, @Valid PointDto point) {
	}

	record RecipientDto(@NotNull String name, String contact) {
	}

	record PackageDto(Integer weightG, Integer lengthCm, Integer widthCm, Integer heightCm, String note) {
	}

	record TimeWindowDto(@NotNull Instant start, @NotNull Instant end) {
	}

	record CreateOrderRequest(@NotNull SaveMode saveMode, @NotNull @Valid AddressDto pickup,
			@NotNull @Valid AddressDto dropoff, @NotNull @Valid RecipientDto recipient,
			@JsonProperty("package") @NotNull @Valid PackageDto pkg, @NotNull @Valid TimeWindowDto timeWindow) {
	}

	record OrderDto(UUID id, String code, OrderStatus status, AddressDto pickup, AddressDto dropoff,
			RecipientDto recipient, @JsonProperty("package") PackageDto pkg, TimeWindowDto timeWindow, UUID zoneId, long version,
			List<String> capabilities, String cancelReason) {
	}

	record UpdateOrderRequest(@NotNull @Valid AddressDto pickup, @NotNull @Valid AddressDto dropoff,
			@NotNull @Valid RecipientDto recipient, @JsonProperty("package") @NotNull @Valid PackageDto pkg,
			@NotNull @Valid TimeWindowDto timeWindow) {
	}

	record CancelOrderRequest(String reason) {
	}

	record TrackingEventDto(String milestone, Instant occurredAt, String note) {
	}

	record OrderEnvelope(OrderDto data, long version, List<String> capabilities) {
	}

	record OrderPage(List<OrderDto> items, String nextCursor, Instant asOf) {
	}

	@PostMapping
	ResponseEntity<OrderEnvelope> create(ActorContext actor,
			@RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
			@Valid @RequestBody CreateOrderRequest request) {
		CreateOrderCommand command = new CreateOrderCommand(
				request.saveMode(),
				request.pickup().address(), toPoint(request.pickup().point()),
				request.dropoff().address(), toPoint(request.dropoff().point()),
				request.recipient().name(), request.recipient().contact(),
				request.pkg().weightG(), request.pkg().lengthCm(), request.pkg().widthCm(),
				request.pkg().heightCm(), request.pkg().note(),
				request.timeWindow().start(), request.timeWindow().end());
		Outcome<Order> outcome = createHandler.handle(actor, IdempotencyKey.fromHeader(idempotencyKey), command);
		ResponseEntity.BodyBuilder response = outcome.replayed()
				? ResponseEntity.ok().header("Idempotent-Replayed", "true")
				: ResponseEntity.created(URI.create("/api/v1/orders/" + outcome.value().id()));
		return response.header(HttpHeaders.ETAG, IfMatch.etag(outcome.value().version()))
				.body(envelope(outcome.value()));
	}

	@PostMapping("/{orderId}/submit")
	ResponseEntity<OrderEnvelope> submit(ActorContext actor, @PathVariable UUID orderId,
			@RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
		Outcome<Order> outcome = submitHandler.handle(actor, orderId, IdempotencyKey.fromHeader(idempotencyKey),
				IfMatch.parse(ifMatch));
		ResponseEntity.BodyBuilder response = ResponseEntity.ok();
		if (outcome.replayed()) {
			response.header("Idempotent-Replayed", "true");
		}
		return response.header(HttpHeaders.ETAG, IfMatch.etag(outcome.value().version()))
				.body(envelope(outcome.value()));
	}

	@PutMapping("/{orderId}")
	ResponseEntity<OrderEnvelope> update(ActorContext actor, @PathVariable UUID orderId,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@Valid @RequestBody UpdateOrderRequest request) {
		Order order = updateHandler.handle(actor, orderId, IfMatch.parse(ifMatch), new UpdateOrderHandler.Command(
				request.pickup().address(), toPoint(request.pickup().point()), request.dropoff().address(),
				toPoint(request.dropoff().point()), request.recipient().name(), request.recipient().contact(),
				request.pkg().weightG(), request.pkg().lengthCm(), request.pkg().widthCm(), request.pkg().heightCm(),
				request.pkg().note(), request.timeWindow().start(), request.timeWindow().end()));
		return ResponseEntity.ok().header(HttpHeaders.ETAG, IfMatch.etag(order.version())).body(envelope(order));
	}

	@PostMapping("/{orderId}/cancel")
	ResponseEntity<OrderEnvelope> cancel(ActorContext actor, @PathVariable UUID orderId,
			@RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@RequestBody(required = false) CancelOrderRequest request) {
		Outcome<Order> outcome = cancelHandler.handle(actor, orderId, IdempotencyKey.fromHeader(idempotencyKey),
				IfMatch.parse(ifMatch), request == null ? null : request.reason());
		ResponseEntity.BodyBuilder response = ResponseEntity.ok();
		if (outcome.replayed()) {
			response.header("Idempotent-Replayed", "true");
		}
		return response.header(HttpHeaders.ETAG, IfMatch.etag(outcome.value().version()))
				.body(envelope(outcome.value()));
	}

	@GetMapping("/{orderId}/tracking")
	java.util.Map<String, Object> tracking(ActorContext actor, @PathVariable UUID orderId) {
		return java.util.Map.of("items", queryHandler.tracking(actor, orderId).stream()
				.map(e -> new TrackingEventDto(e.milestone(), e.occurredAt(), e.note())).toList());
	}

	@GetMapping("/{orderId}")
	ResponseEntity<OrderDto> get(ActorContext actor, @PathVariable UUID orderId) {
		Order order = queryHandler.get(actor, orderId);
		return ResponseEntity.ok().header(HttpHeaders.ETAG, IfMatch.etag(order.version())).body(toDto(order));
	}

	@GetMapping
	OrderPage list(ActorContext actor,
			@RequestParam(required = false) OrderStatus status,
			@RequestParam(required = false) UUID zoneId,
			@RequestParam(required = false, name = "q") String codePrefix,
			@RequestParam(required = false) Instant windowFrom,
			@RequestParam(required = false) Instant windowTo,
			@RequestParam(required = false) Integer limit,
			@RequestParam(required = false) String cursor) {
		PageResult<Order> page = queryHandler.list(actor, status, zoneId, codePrefix, windowFrom, windowTo, limit, cursor);
		return new OrderPage(page.items().stream().map(OrderController::toDto).toList(), page.nextCursor(), page.asOf());
	}

	private static GeoPoint toPoint(PointDto dto) {
		return dto == null ? null : dto.toDomain();
	}

	private static OrderEnvelope envelope(Order order) {
		return new OrderEnvelope(toDto(order), order.version(), capabilities(order));
	}

	private static OrderDto toDto(Order o) {
		return new OrderDto(o.id(), o.code(), o.status(),
				new AddressDto(o.pickupAddress(), o.pickup() == null ? null : new PointDto(o.pickup().lat(), o.pickup().lng())),
				new AddressDto(o.dropoffAddress(), o.dropoff() == null ? null : new PointDto(o.dropoff().lat(), o.dropoff().lng())),
				new RecipientDto(o.recipientName(), o.recipientContact()),
				new PackageDto(o.weightG(), o.lengthCm(), o.widthCm(), o.heightCm(), o.packageNote()),
				new TimeWindowDto(o.windowStart(), o.windowEnd()), o.zoneId(), o.version(), capabilities(o),
				o.cancelReason());
	}

	private static List<String> capabilities(Order o) {
		List<String> result = new java.util.ArrayList<>();
		if (o.isEditable()) {
			result.add("canEditOrder");
		}
		if (o.isCancellableByOwner()) {
			result.add("canCancelOrder");
		}
		if (o.status() == OrderStatus.DRAFT) {
			result.add("canSubmitOrder");
		}
		return result;
	}
}
