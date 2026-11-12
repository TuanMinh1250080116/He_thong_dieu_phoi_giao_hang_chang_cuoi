package vn.edu.finalism.logistics.bootstrap;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.CorrelationId;
import vn.edu.finalism.logistics.shared.ErrorCode;

@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(BusinessException.class)
	ResponseEntity<Object> handleBusiness(BusinessException ex) {
		ProblemDetail problem = problem(ex.code(), ex.getMessage());
		if (ex.code() == ErrorCode.VALIDATION_ERROR && ex.details().containsKey("field")) {
			problem.setProperty("errors", List.of(Map.of(
					"field", ex.details().get("field"),
					"code", ex.details().getOrDefault("fieldCode", "INVALID"),
					"message", String.valueOf(ex.getMessage()))));
		}
		else if (!ex.details().isEmpty()) {
			problem.setProperty("details", ex.details());
		}
		if (ex.details().containsKey("currentVersion")) {
			problem.setProperty("currentVersion", ex.details().get("currentVersion"));
		}
		ResponseEntity.BodyBuilder response = ResponseEntity.status(problem.getStatus())
				.contentType(MediaType.APPLICATION_PROBLEM_JSON);
		Object retryAfter = ex.details().get("retryAfterSeconds");
		if (retryAfter != null) {
			response.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
		}
		return response.body(problem);
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex) {
		return respond(problem(ErrorCode.FORBIDDEN, "You do not have permission for this action."));
	}

	@ExceptionHandler(DataAccessException.class)
	ResponseEntity<Object> handleDataAccess(DataAccessException ex) {
		return ConstraintErrorMapper.map(ex)
				.map(code -> respond(problem(code, "The request conflicts with the current state.")))
				.orElseGet(() -> handleUnexpected(ex));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> handleUnexpected(Exception ex) {
		log.error("Unexpected failure", ex);
		return respond(problem(ErrorCode.UNEXPECTED_FAILURE, "An unexpected error occurred."));
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = problem(ErrorCode.VALIDATION_ERROR, "Request validation failed.");
		List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(fe -> Map.of(
						"field", fe.getField(),
						"code", fe.getCode() == null ? "INVALID" : fe.getCode(),
						"message", String.valueOf(fe.getDefaultMessage())))
				.toList();
		problem.setProperty("errors", errors);
		return respond(problem);
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
		if (response != null && response.getBody() instanceof ProblemDetail problem
				&& (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
			problem.setProperty("correlationId", MDC.get(CorrelationId.MDC_KEY));
			if (statusCode.value() == 400) {
				problem.setProperty("code", ErrorCode.VALIDATION_ERROR.name());
			}
			else if (statusCode.value() == 404) {
				problem.setProperty("code", ErrorCode.RESOURCE_NOT_FOUND.name());
			}
		}
		return response;
	}

	private static ResponseEntity<Object> respond(ProblemDetail problem) {
		return ResponseEntity.status(problem.getStatus())
				.contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.body(problem);
	}

	private static ProblemDetail problem(ErrorCode code, String detail) {
		HttpStatus status = statusOf(code);
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(URI.create("https://api.example.invalid/problems/"
				+ code.name().toLowerCase().replace('_', '-')));
		problem.setTitle(status.getReasonPhrase());
		problem.setProperty("code", code.name());
		problem.setProperty("correlationId", MDC.get(CorrelationId.MDC_KEY));
		problem.setProperty("retryable", code.retryable());
		return problem;
	}

	static HttpStatus statusOf(ErrorCode code) {
		return switch (code) {
			case VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
			case AUTHENTICATION_REQUIRED, AUTHENTICATION_FAILED -> HttpStatus.UNAUTHORIZED;
			case ACCOUNT_LOCKED, OTP_LOCKED -> HttpStatus.LOCKED;
			case ACCOUNT_DISABLED, FORBIDDEN -> HttpStatus.FORBIDDEN;
			case RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
			case PRECONDITION_REQUIRED -> HttpStatus.PRECONDITION_REQUIRED;
			case VERSION_CONFLICT, STATE_TRANSITION_NOT_ALLOWED, IDEMPOTENCY_IN_PROGRESS,
					ORDER_ALREADY_IN_ACTIVE_BATCH, ASSIGNMENT_CONFLICT, DRIVER_INELIGIBLE,
					RECOMMENDATION_EXPIRED, RECOMMENDATION_INVALIDATED,
					COMPLETION_PREREQUISITES_MISSING -> HttpStatus.CONFLICT;
			case IDEMPOTENCY_KEY_REUSED, ZONE_UNRESOLVED, BATCH_LOCK_VALIDATION_FAILED,
					POD_POLICY_NOT_SATISFIED, OTP_INVALID_OR_EXPIRED, EVIDENCE_INVALID -> HttpStatus.UNPROCESSABLE_ENTITY;
			case UPLOAD_FAILED -> HttpStatus.BAD_GATEWAY;
			case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
			case EXTERNAL_DEPENDENCY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
			case UNEXPECTED_FAILURE -> HttpStatus.INTERNAL_SERVER_ERROR;
		};
	}
}
