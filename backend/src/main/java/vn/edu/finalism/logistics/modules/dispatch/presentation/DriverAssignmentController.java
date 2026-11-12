package vn.edu.finalism.logistics.modules.dispatch.presentation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import vn.edu.finalism.logistics.modules.dispatch.application.RejectAssignmentHandler;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.edu.finalism.logistics.modules.dispatch.application.AcceptAssignmentHandler;
import vn.edu.finalism.logistics.modules.dispatch.application.AssignmentQueryHandler;
import vn.edu.finalism.logistics.modules.dispatch.application.AssignmentResult;
import vn.edu.finalism.logistics.modules.dispatch.presentation.DispatchController.AssignmentDto;
import vn.edu.finalism.logistics.modules.dispatch.presentation.DispatchController.AssignmentEnvelope;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.IdempotencyKey;
import vn.edu.finalism.logistics.shared.IfMatch;
import vn.edu.finalism.logistics.shared.Outcome;

@RestController
class DriverAssignmentController {

	private final AcceptAssignmentHandler accept;
	private final AssignmentQueryHandler query;
	private final ClockPort clock;
	private final RejectAssignmentHandler rejectHandler;

	DriverAssignmentController(AcceptAssignmentHandler accept, AssignmentQueryHandler query, ClockPort clock,
			RejectAssignmentHandler rejectHandler) {
		this.rejectHandler = rejectHandler;
		this.accept = accept;
		this.query = query;
		this.clock = clock;
	}

	record AssignmentPage(List<AssignmentDto> items, String nextCursor, Instant asOf) {
	}

	@GetMapping("/api/v1/me/deliveries")
	Map<String, Object> myDeliveries(ActorContext actor, @RequestParam(required = false) List<String> status,
			@RequestParam(required = false) java.time.Instant from, @RequestParam(required = false) java.time.Instant to,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		var page = query.listMyDeliveries(actor, status, from, to, cursor, limit);
		Map<String, Object> body = new java.util.LinkedHashMap<>();
		body.put("items", page.items());
		body.put("nextCursor", page.nextCursor());
		body.put("asOf", page.asOf());
		return body;
	}

	@GetMapping("/api/v1/me/deliveries/{deliveryId}")
	Map<String, Object> myDelivery(ActorContext actor, @PathVariable UUID deliveryId) {
		return query.myDeliveryOverview(actor, deliveryId);
	}

	@GetMapping("/api/v1/me/assignments")
	AssignmentPage mine(ActorContext actor, @RequestParam(required = false) Integer limit) {
		List<AssignmentDto> items = query.listMine(actor, limit).stream()
				.map(row -> new AssignmentDto(row.assignment().id(), row.assignment().decisionId(), row.deliveryId(),
						row.assignment().driverId(), row.assignment().status().name(), row.assignment().kind(),
						row.decisionVersion()))
				.toList();
		return new AssignmentPage(items, null, clock.now());
	}

	record RejectRequest(@NotNull String reasonCode, String description) {
	}

	record ReasonDto(String code, boolean requiresDescription) {
	}

	@GetMapping("/api/v1/catalogs/rejection-reasons")
	Map<String, Object> rejectionReasons(ActorContext actor) {
		List<ReasonDto> items = query.rejectionReasons(actor).stream()
				.map(r -> new ReasonDto(r.code(), r.requiresDescription())).toList();
		return Map.of("items", items);
	}

	@PostMapping("/api/v1/assignments/{assignmentId}/reject")
	ResponseEntity<AssignmentEnvelope> reject(ActorContext actor, @PathVariable UUID assignmentId,
			@RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@Valid @RequestBody RejectRequest request) {
		Outcome<AssignmentResult> outcome = rejectHandler.handle(actor, assignmentId,
				IdempotencyKey.fromHeader(idempotencyKey), IfMatch.parse(ifMatch), request.reasonCode(),
				request.description());
		ResponseEntity.BodyBuilder response = ResponseEntity.ok();
		if (outcome.replayed()) {
			response.header("Idempotent-Replayed", "true");
		}
		return response.header(HttpHeaders.ETAG, IfMatch.etag(outcome.value().decisionVersion()))
				.body(DispatchController.assignmentEnvelope(outcome.value()));
	}

	@PostMapping("/api/v1/assignments/{assignmentId}/accept")
	ResponseEntity<AssignmentEnvelope> accept(ActorContext actor, @PathVariable UUID assignmentId,
			@RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
		Outcome<AssignmentResult> outcome = accept.handle(actor, assignmentId,
				IdempotencyKey.fromHeader(idempotencyKey), IfMatch.parse(ifMatch));
		ResponseEntity.BodyBuilder response = ResponseEntity.ok();
		if (outcome.replayed()) {
			response.header("Idempotent-Replayed", "true");
		}
		return response.header(HttpHeaders.ETAG, IfMatch.etag(outcome.value().decisionVersion()))
				.body(DispatchController.assignmentEnvelope(outcome.value()));
	}
}
