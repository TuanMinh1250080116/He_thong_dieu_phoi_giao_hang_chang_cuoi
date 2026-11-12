package vn.edu.finalism.logistics.modules.exception.presentation;

import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import vn.edu.finalism.logistics.modules.exception.application.AddExceptionNoteHandler;
import vn.edu.finalism.logistics.modules.exception.application.ExceptionDetail;
import vn.edu.finalism.logistics.modules.exception.application.ExceptionQueryHandler;
import vn.edu.finalism.logistics.modules.exception.application.ManageExceptionHandler;
import vn.edu.finalism.logistics.modules.exception.application.ReportExceptionHandler;
import vn.edu.finalism.logistics.modules.exception.application.ReportExceptionHandler.ReportCommand;
import vn.edu.finalism.logistics.modules.exception.application.port.ExceptionRepository.ExceptionView;
import vn.edu.finalism.logistics.modules.exception.domain.DeliveryException;
import vn.edu.finalism.logistics.modules.exception.domain.ExceptionStatus;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.IdempotencyKey;
import vn.edu.finalism.logistics.shared.IfMatch;
import vn.edu.finalism.logistics.shared.Outcome;
import vn.edu.finalism.logistics.shared.PageResult;

@RestController
@RequestMapping("/api/v1")
class ExceptionController {

	private final ReportExceptionHandler report;
	private final ManageExceptionHandler manage;
	private final AddExceptionNoteHandler addNote;
	private final ExceptionQueryHandler query;

	ExceptionController(ReportExceptionHandler report, ManageExceptionHandler manage, AddExceptionNoteHandler addNote,
			ExceptionQueryHandler query) {
		this.report = report;
		this.manage = manage;
		this.addNote = addNote;
		this.query = query;
	}

	record ReportRequest(@NotNull String type, @NotNull String description, UUID stopId, UUID orderId, String severity,
			List<UUID> evidenceIds) {
	}

	record InvestigateRequest(@NotNull UUID assigneeId, @NotNull String note) {
	}

	record ResolveRequest(@NotNull String outcome, @NotNull String note) {
	}

	record CancelRequest(@NotNull String reason) {
	}

	record NoteRequest(@NotNull String body) {
	}

	@PostMapping("/deliveries/{deliveryId}/exceptions")
	ResponseEntity<Map<String, Object>> report(ActorContext actor, @PathVariable UUID deliveryId,
			@RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
			@Valid @RequestBody ReportRequest request) {
		Outcome<ExceptionDetail> outcome = report.handle(actor, deliveryId, IdempotencyKey.fromHeader(idempotencyKey),
				new ReportCommand(request.type(), request.description(), request.stopId(), request.orderId(),
						request.severity(), request.evidenceIds()));
		ExceptionDetail detail = outcome.value();
		ResponseEntity.BodyBuilder response = outcome.replayed()
				? ResponseEntity.ok().header("Idempotent-Replayed", "true")
				: ResponseEntity.created(java.net.URI.create("/api/v1/exceptions/" + detail.view().exception().id()));
		return response.header(HttpHeaders.ETAG, IfMatch.etag(detail.view().exception().version()))
				.body(envelope(actor, detail));
	}

	@GetMapping("/exceptions")
	PageResult<Map<String, Object>> queue(ActorContext actor, @RequestParam(required = false) String status,
			@RequestParam(required = false) String severity, @RequestParam(required = false) String type,
			@RequestParam(required = false) UUID assigneeId, @RequestParam(required = false) Boolean blocking,
			@RequestParam(required = false) UUID deliveryId, @RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		PageResult<ExceptionView> page = query.queue(actor, new ExceptionQueryHandler.Query(status, severity, type,
				assigneeId, blocking, deliveryId, from, to, cursor, limit));
		return new PageResult<>(page.items().stream().map(v -> summary(v)).toList(), page.nextCursor(), page.asOf());
	}

	@GetMapping("/exceptions/{exceptionId}")
	ResponseEntity<Map<String, Object>> get(ActorContext actor, @PathVariable UUID exceptionId) {
		ExceptionDetail detail = query.detail(actor, exceptionId);
		return ResponseEntity.ok().header(HttpHeaders.ETAG, IfMatch.etag(detail.view().exception().version()))
				.body(envelope(actor, detail));
	}

	@PostMapping("/exceptions/{exceptionId}/notes")
	ResponseEntity<Map<String, Object>> note(ActorContext actor, @PathVariable UUID exceptionId,
			@Valid @RequestBody NoteRequest request) {
		ExceptionDetail detail = addNote.handle(actor, exceptionId, request.body());
		return ResponseEntity.status(201).body(envelope(actor, detail));
	}

	@PostMapping("/exceptions/{exceptionId}/investigate")
	ResponseEntity<Map<String, Object>> investigate(ActorContext actor, @PathVariable UUID exceptionId,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@Valid @RequestBody InvestigateRequest request) {
		ExceptionDetail detail = manage.investigate(actor, exceptionId, IfMatch.parse(ifMatch), request.assigneeId(),
				request.note());
		return ok(actor, detail, false);
	}

	@PostMapping("/exceptions/{exceptionId}/resolve")
	ResponseEntity<Map<String, Object>> resolve(ActorContext actor, @PathVariable UUID exceptionId,
			@RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@Valid @RequestBody ResolveRequest request) {
		Outcome<ExceptionDetail> outcome = manage.resolve(actor, exceptionId, IdempotencyKey.fromHeader(idempotencyKey),
				IfMatch.parse(ifMatch), request.outcome(), request.note());
		return ok(actor, outcome.value(), outcome.replayed());
	}

	@PostMapping("/exceptions/{exceptionId}/cancel")
	ResponseEntity<Map<String, Object>> cancel(ActorContext actor, @PathVariable UUID exceptionId,
			@RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
			@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@Valid @RequestBody CancelRequest request) {
		Outcome<ExceptionDetail> outcome = manage.cancel(actor, exceptionId, IdempotencyKey.fromHeader(idempotencyKey),
				IfMatch.parse(ifMatch), request.reason());
		return ok(actor, outcome.value(), outcome.replayed());
	}

	@GetMapping("/catalogs/exception-types")
	Map<String, Object> types() {
		return Map.of("items", query.types().stream().map(t -> {
			Map<String, Object> dto = new LinkedHashMap<>();
			dto.put("code", t.code());
			dto.put("name", t.name());
			dto.put("defaultSeverity", t.severity());
			dto.put("impact", t.impact());
			return dto;
		}).toList());
	}

	private ResponseEntity<Map<String, Object>> ok(ActorContext actor, ExceptionDetail detail, boolean replayed) {
		ResponseEntity.BodyBuilder response = ResponseEntity.ok();
		if (replayed) {
			response.header("Idempotent-Replayed", "true");
		}
		return response.header(HttpHeaders.ETAG, IfMatch.etag(detail.view().exception().version()))
				.body(envelope(actor, detail));
	}

	private static Map<String, Object> envelope(ActorContext actor, ExceptionDetail detail) {
		DeliveryException e = detail.view().exception();
		Map<String, Object> data = new LinkedHashMap<>(summary(detail.view()));
		data.put("notes", detail.notes().stream().map(n -> {
			Map<String, Object> note = new LinkedHashMap<>();
			note.put("id", n.id());
			note.put("type", n.type());
			note.put("body", n.body());
			note.put("author", Map.of("id", n.authorId(), "name", n.authorName()));
			note.put("createdAt", n.createdAt());
			return note;
		}).toList());
		data.put("evidenceIds", detail.evidenceIds());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("data", data);
		body.put("version", e.version());
		body.put("capabilities", capabilities(actor, detail));
		return body;
	}

	private static Map<String, Object> summary(ExceptionView v) {
		DeliveryException e = v.exception();
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("id", e.id());
		dto.put("code", e.code());
		dto.put("deliveryId", e.deliveryId());
		dto.put("deliveryCode", v.deliveryCode());
		dto.put("stopId", e.stopId());
		dto.put("stopStatus", v.stopStatus());
		dto.put("orderId", e.orderId());
		dto.put("type", Map.of("code", e.typeCode(), "name", v.typeName()));
		dto.put("severity", e.severity());
		dto.put("impact", e.impact());
		dto.put("blocking", e.blocking());
		dto.put("status", e.status());
		dto.put("description", e.description());
		dto.put("reporter", Map.of("id", e.reporterUserId(), "name", v.reporterName()));
		dto.put("assignee", e.assigneeUserId() == null ? null
				: Map.of("id", e.assigneeUserId(), "name", v.assigneeName()));
		dto.put("reportedAt", e.reportedAt());
		dto.put("investigatingAt", e.investigatingAt());
		dto.put("closedAt", e.closedAt());
		dto.put("resolutionOutcome", e.resolutionOutcome());
		dto.put("resolutionNote", e.resolutionNote());
		dto.put("cancellationReason", e.cancellationReason());
		dto.put("version", e.version());
		return dto;
	}

	private static List<String> capabilities(ActorContext actor, ExceptionDetail detail) {
		DeliveryException e = detail.view().exception();
		boolean manager = actor.hasPermission("exception.manage") && actor.hasScope(detail.view().zoneId());
		List<String> result = new ArrayList<>();
		if (manager && e.status() == ExceptionStatus.OPEN) {
			result.add("canInvestigateException");
			result.add("canCancelException");
		}
		if (manager && e.status() == ExceptionStatus.INVESTIGATING) {
			result.add("canResolveException");
		}
		if (actor.hasPermission("exception.context.add") && e.status().isOpenState()) {
			result.add("canAddContext");
		}
		boolean closed = e.status() == ExceptionStatus.RESOLVED || e.status() == ExceptionStatus.CANCELLED;
		if (closed && detail.actorIsAssignedDriver() && "STOP".equals(e.blockingScope())
				&& "BLOCKED".equals(detail.view().stopStatus())) {
			result.add("canResumeStop");
		}
		return result;
	}
}
