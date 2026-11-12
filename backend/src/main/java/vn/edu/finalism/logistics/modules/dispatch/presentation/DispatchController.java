package vn.edu.finalism.logistics.modules.dispatch.presentation;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import vn.edu.finalism.logistics.modules.dispatch.application.AssignRecommendedDriverHandler;
import vn.edu.finalism.logistics.modules.dispatch.application.AssignmentResult;
import vn.edu.finalism.logistics.modules.dispatch.application.BatchLifecycleHandler;
import vn.edu.finalism.logistics.modules.dispatch.application.EligibleOrdersHandler;
import vn.edu.finalism.logistics.modules.dispatch.application.BatchMembershipHandler;
import vn.edu.finalism.logistics.modules.dispatch.application.BatchQueryHandler;
import vn.edu.finalism.logistics.modules.order.application.OrderLifecycleService.OrderView;
import vn.edu.finalism.logistics.shared.PageResult;
import org.springframework.web.bind.annotation.RequestParam;
import vn.edu.finalism.logistics.modules.dispatch.application.CancelDispatchHandler;
import vn.edu.finalism.logistics.modules.dispatch.application.CreateBatchHandler;
import vn.edu.finalism.logistics.modules.dispatch.application.ReassignDriverHandler;
import vn.edu.finalism.logistics.modules.dispatch.application.GenerateRecommendationsHandler;
import vn.edu.finalism.logistics.modules.dispatch.application.LockBatchHandler;
import vn.edu.finalism.logistics.modules.dispatch.application.RecommendationQueryHandler;
import vn.edu.finalism.logistics.modules.dispatch.application.RecommendationResult;
import vn.edu.finalism.logistics.modules.dispatch.domain.BatchStatus;
import vn.edu.finalism.logistics.modules.dispatch.domain.Candidate;
import vn.edu.finalism.logistics.modules.dispatch.domain.DeliveryBatch;
import vn.edu.finalism.logistics.modules.dispatch.domain.Snapshot;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.IdempotencyKey;
import vn.edu.finalism.logistics.shared.IfMatch;
import vn.edu.finalism.logistics.shared.Outcome;

@RestController
@RequestMapping("/api/v1/dispatch")
class DispatchController {

	private final CreateBatchHandler createBatch;
	private final LockBatchHandler lockBatch;
	private final BatchQueryHandler batchQuery;
	private final GenerateRecommendationsHandler generate;
	private final RecommendationQueryHandler recommendationQuery;
	private final AssignRecommendedDriverHandler assign;
	private final ReassignDriverHandler reassign;
	private final CancelDispatchHandler cancelDispatch;
	private final BatchMembershipHandler membership;
	private final BatchLifecycleHandler batchLifecycle;
	private final EligibleOrdersHandler eligibleOrders;

	DispatchController(BatchMembershipHandler membership, BatchLifecycleHandler batchLifecycle,
			EligibleOrdersHandler eligibleOrders, CreateBatchHandler createBatch, LockBatchHandler lockBatch, BatchQueryHandler batchQuery,
			GenerateRecommendationsHandler generate, RecommendationQueryHandler recommendationQuery,
			AssignRecommendedDriverHandler assign, ReassignDriverHandler reassign,
			CancelDispatchHandler cancelDispatch) {
		this.membership = membership;
		this.batchLifecycle = batchLifecycle;
		this.eligibleOrders = eligibleOrders;
		this.reassign = reassign;
		this.cancelDispatch = cancelDispatch;
		this.createBatch = createBatch;
		this.lockBatch = lockBatch;
		this.batchQuery = batchQuery;
		this.generate = generate;
		this.recommendationQuery = recommendationQuery;
		this.assign = assign;
	}

	record CreateBatchRequest(@NotNull UUID zoneId, @NotEmpty List<UUID> orderIds) {
	}

	record AssignRequest(@NotNull UUID snapshotId, UUID driverId, String overrideReason) {
	}

	record ReassignRequest(@NotNull UUID driverId, @NotNull String reason) {
	}

	record CancelRequest(@NotNull String reason) {
	}

	record MembershipRequest(List<UUID> addOrderIds, List<UUID> removeOrderIds) {
	}

	record WindowDto(Instant start, Instant end) {
	}

	record BatchDto(UUID id, String code, UUID zoneId, BatchStatus status, int orderCount, long totalWeightG,
			long totalVolumeCm3, WindowDto plannedWindow, List<UUID> orderIds, UUID decisionId, long version,
			List<String> capabilities) {
	}

	record BatchEnvelope(BatchDto data, long version, List<String> capabilities) {
	}

	record AssignmentDto(UUID id, UUID decisionId, UUID deliveryId, UUID driverId, String status, String kind,
			long version) {
	}

	record AssignmentEnvelope(AssignmentDto data, long version, List<String> capabilities) {
	}

	@PostMapping("/batches")
	ResponseEntity<BatchEnvelope> createBatch(ActorContext actor,
			@RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
			@Valid @RequestBody CreateBatchRequest request) {
		Outcome<DeliveryBatch> outcome = createBatch.handle(actor, IdempotencyKey.fromHeader(idempotencyKey),
				request.zoneId(), request.orderIds());
		DeliveryBatch batch = outcome.value();
		ResponseEntity.BodyBuilder response = outcome.replayed()
				? ResponseEntity.ok().header("Idempotent-Replayed", "true")
				: ResponseEntity.created(URI.create("/api/v1/dispatch/batches/" + batch.id()));
		return response.header(HttpHeaders.ETAG, IfMatch.etag(batch.version())).body(batchEnvelope(batch));
	}

	@GetMapping("/eligible-orders")
	Map<String, Object> eligibleOrders(ActorContext actor, @RequestParam(required = false) UUID zoneId,
			@RequestParam(required = false) Instant windowFrom, @RequestParam(required = false) Instant windowTo,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		PageResult<OrderView> page = eligibleOrders.handle(actor, zoneId, windowFrom, windowTo, cursor, limit);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("items", page.items().stream().map(o -> {
			Map<String, Object> dto = new LinkedHashMap<>();
			dto.put("id", o.id());
			dto.put("code", o.code());
			dto.put("zoneId", o.zoneId());
			dto.put("weightG", o.weightG());
			dto.put("volumeCm3", o.volumeCm3());
			dto.put("windowStart", o.windowStart());
			dto.put("windowEnd", o.windowEnd());
			dto.put("pickupAddress", o.pickupAddress());
			dto.put("dropoffAddress", o.dropoffAddress());
			dto.put("pickup", o.pickup() == null ? null : Map.of("lat", o.pickup().lat(), "lng", o.pickup().lng()));
			dto.put("dropoff", o.dropoff() == null ? null : Map.of("lat", o.dropoff().lat(), "lng", o.dropoff().lng()));
			return dto;
		}).toList());
		body.put("nextCursor", page.nextCursor());
		body.put("asOf", page.asOf());
		return body;
	}

	@PostMapping("/batches/{batchId}/membership")
	ResponseEntity<BatchEnvelope> changeMembership(ActorContext actor, @PathVariable UUID batchId,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@Valid @RequestBody MembershipRequest request) {
		DeliveryBatch batch = membership.handle(actor, batchId, IfMatch.parse(ifMatch), request.addOrderIds(),
				request.removeOrderIds());
		return ResponseEntity.ok().header(HttpHeaders.ETAG, IfMatch.etag(batch.version())).body(batchEnvelope(batch));
	}

	@PostMapping("/batches/{batchId}/reopen")
	ResponseEntity<BatchEnvelope> reopen(ActorContext actor, @PathVariable UUID batchId,
			@RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@Valid @RequestBody CancelRequest request) {
		return batchResponse(batchLifecycle.reopen(actor, batchId, IdempotencyKey.fromHeader(idempotencyKey),
				IfMatch.parse(ifMatch), request.reason()));
	}

	@PostMapping("/batches/{batchId}/cancel")
	ResponseEntity<BatchEnvelope> cancelBatch(ActorContext actor, @PathVariable UUID batchId,
			@RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@Valid @RequestBody CancelRequest request) {
		return batchResponse(batchLifecycle.cancel(actor, batchId, IdempotencyKey.fromHeader(idempotencyKey),
				IfMatch.parse(ifMatch), request.reason()));
	}

	@GetMapping("/batches/{batchId}")
	ResponseEntity<BatchDto> getBatch(ActorContext actor, @PathVariable UUID batchId) {
		DeliveryBatch batch = batchQuery.get(actor, batchId);
		return ResponseEntity.ok().header(HttpHeaders.ETAG, IfMatch.etag(batch.version())).body(batchDto(batch));
	}

	@PostMapping("/batches/{batchId}/lock")
	ResponseEntity<BatchEnvelope> lock(ActorContext actor, @PathVariable UUID batchId,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
		DeliveryBatch batch = lockBatch.handle(actor, batchId, IfMatch.parse(ifMatch));
		return ResponseEntity.ok().header(HttpHeaders.ETAG, IfMatch.etag(batch.version())).body(batchEnvelope(batch));
	}

	@PostMapping("/batches/{batchId}/recommendations")
	ResponseEntity<Map<String, Object>> generate(ActorContext actor, @PathVariable UUID batchId,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
		RecommendationResult result = generate.handle(actor, batchId, IfMatch.parse(ifMatch));
		return ResponseEntity.status(201).header(HttpHeaders.ETAG, IfMatch.etag(result.decisionVersion()))
				.body(recommendationEnvelope(result));
	}

	@GetMapping("/batches/{batchId}/recommendations/current")
	ResponseEntity<Map<String, Object>> current(ActorContext actor, @PathVariable UUID batchId) {
		RecommendationResult result = recommendationQuery.current(actor, batchId);
		return ResponseEntity.ok().header(HttpHeaders.ETAG, IfMatch.etag(result.decisionVersion()))
				.body(recommendationEnvelope(result));
	}

	@GetMapping("/batches/{batchId}/decision")
	ResponseEntity<Map<String, Object>> decision(ActorContext actor, @PathVariable UUID batchId) {
		RecommendationQueryHandler.DecisionView view = recommendationQuery.decision(actor, batchId);
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("decisionId", view.decisionId());
		data.put("status", view.status());
		data.put("assignment", view.assignment().map(a -> {
			Map<String, Object> dto = new LinkedHashMap<>();
			dto.put("id", a.id());
			dto.put("status", a.status());
			dto.put("kind", a.kind());
			dto.put("driverId", a.driverId());
			dto.put("driverName", a.driverName());
			dto.put("rejectionReasonCode", a.rejectionReasonCode());
			return dto;
		}).orElse(null));
		List<String> capabilities = switch (view.status()) {
			case PENDING_DECISION -> List.of("canCancelDispatch");
			case AWAITING_DRIVER_RESPONSE, CONFIRMED -> List.of("canReassign", "canCancelDispatch");
			case AWAITING_REASSIGNMENT -> List.of("canReassign", "canCancelDispatch");
			default -> List.of();
		};
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("data", data);
		body.put("version", view.version());
		body.put("capabilities", capabilities);
		return ResponseEntity.ok().header(HttpHeaders.ETAG, IfMatch.etag(view.version())).body(body);
	}

	@PostMapping("/decisions/{decisionId}/assign")
	ResponseEntity<AssignmentEnvelope> assign(ActorContext actor, @PathVariable UUID decisionId,
			@RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@Valid @RequestBody AssignRequest request) {
		Outcome<AssignmentResult> outcome = assign.handle(actor, decisionId,
				IdempotencyKey.fromHeader(idempotencyKey), IfMatch.parse(ifMatch), request.snapshotId(),
				request.driverId(), request.overrideReason());
		return assignmentResponse(outcome);
	}

	@PostMapping("/decisions/{decisionId}/reassign")
	ResponseEntity<AssignmentEnvelope> reassign(ActorContext actor, @PathVariable UUID decisionId,
			@RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@Valid @RequestBody ReassignRequest request) {
		return assignmentResponse(reassign.handle(actor, decisionId, IdempotencyKey.fromHeader(idempotencyKey),
				IfMatch.parse(ifMatch), request.driverId(), request.reason()));
	}

	@PostMapping("/decisions/{decisionId}/cancel")
	ResponseEntity<Map<String, Object>> cancel(ActorContext actor, @PathVariable UUID decisionId,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@Valid @RequestBody CancelRequest request) {
		long version = cancelDispatch.handle(actor, decisionId, IfMatch.parse(ifMatch), request.reason());
		return ResponseEntity.ok().header(HttpHeaders.ETAG, IfMatch.etag(version))
				.body(Map.of("data", Map.of("decisionId", decisionId, "status", "CANCELLED"), "version", version,
						"capabilities", List.of()));
	}

	private static ResponseEntity<AssignmentEnvelope> assignmentResponse(Outcome<AssignmentResult> outcome) {
		ResponseEntity.BodyBuilder response = ResponseEntity.ok();
		if (outcome.replayed()) {
			response.header("Idempotent-Replayed", "true");
		}
		return response.header(HttpHeaders.ETAG, IfMatch.etag(outcome.value().decisionVersion()))
				.body(assignmentEnvelope(outcome.value()));
	}

	static AssignmentEnvelope assignmentEnvelope(AssignmentResult result) {
		var a = result.assignment();
		AssignmentDto dto = new AssignmentDto(a.id(), a.decisionId(), result.deliveryId(), a.driverId(),
				a.status().name(), a.kind(), result.decisionVersion());
		List<String> capabilities = a.status() == vn.edu.finalism.logistics.modules.dispatch.domain.Assignment.AssignmentStatus.PENDING
				? List.of("canAcceptAssignment", "canRejectAssignment") : List.of();
		return new AssignmentEnvelope(dto, result.decisionVersion(), capabilities);
	}

	private static ResponseEntity<BatchEnvelope> batchResponse(Outcome<DeliveryBatch> outcome) {
		ResponseEntity.BodyBuilder response = ResponseEntity.ok();
		if (outcome.replayed()) {
			response.header("Idempotent-Replayed", "true");
		}
		return response.header(HttpHeaders.ETAG, IfMatch.etag(outcome.value().version()))
				.body(batchEnvelope(outcome.value()));
	}

	private static BatchEnvelope batchEnvelope(DeliveryBatch batch) {
		return new BatchEnvelope(batchDto(batch), batch.version(), capabilities(batch));
	}

	private static BatchDto batchDto(DeliveryBatch b) {
		WindowDto window = b.plannedStart() == null ? null : new WindowDto(b.plannedStart(), b.plannedEnd());
		return new BatchDto(b.id(), b.code(), b.zoneId(), b.status(), b.orderIds().size(), b.totalWeightG(),
				b.totalVolumeCm3(), window, b.orderIds(), null, b.version(), capabilities(b));
	}

	private static List<String> capabilities(DeliveryBatch batch) {
		return switch (batch.status()) {
			case DRAFT -> List.of("canLockBatch", "canEditBatchMembers", "canCancelBatch");
			case LOCKED -> List.of("canGenerateRecommendation", "canReopenBatch", "canCancelBatch");
			default -> List.of();
		};
	}

	private static Map<String, Object> recommendationEnvelope(RecommendationResult result) {
		Snapshot s = result.snapshot();
		Instant now = Instant.now();
		String effective = s.effectiveStatus(now);
		List<Map<String, Object>> candidates = s.candidates().stream().map(DispatchController::candidateDto).toList();
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("id", s.id());
		snapshot.put("status", s.status());
		snapshot.put("effectiveStatus", effective);
		snapshot.put("generatedAt", s.generatedAt());
		snapshot.put("expiresAt", s.expiresAt());
		snapshot.put("ruleVersion", Map.of("id", s.ruleVersionId(), "versionNo", s.ruleVersionNo()));
		snapshot.put("candidateCount", s.candidateCount());
		snapshot.put("eligibleCount", s.eligibleCount());
		snapshot.put("exclusionSummary", s.exclusionSummary());
		snapshot.put("outcome", s.outcome());
		snapshot.put("candidates", candidates);
		boolean canAssign = "ACTIVE".equals(effective) && !s.candidates().isEmpty();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("data", Map.of("decisionId", result.decisionId(), "snapshot", snapshot));
		body.put("version", result.decisionVersion());
		body.put("capabilities", canAssign ? List.of("canAssign") : List.of());
		return body;
	}

	private static Map<String, Object> candidateDto(Candidate c) {
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("rank", c.rank());
		dto.put("driverId", c.driverId());
		dto.put("driverName", c.driverName());
		dto.put("vehicle", Map.of("id", c.vehicleId(), "type", c.vehicleType()));
		dto.put("totalScore", c.totalScore());
		dto.put("distanceM", c.distanceM());
		dto.put("distanceBasis", "GEODESIC");
		dto.put("breakdown", Map.of("distance", c.scoreDistance(), "capacityFit", c.scoreCapacityFit(),
				"availability", c.scoreAvailability(), "zoneCompatibility", c.scoreZoneCompat()));
		return dto;
	}
}
