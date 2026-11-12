package vn.edu.finalism.logistics.shared;

public enum ErrorCode {

	VALIDATION_ERROR(false),
	AUTHENTICATION_REQUIRED(false),
	AUTHENTICATION_FAILED(false),
	ACCOUNT_LOCKED(true),
	ACCOUNT_DISABLED(false),
	FORBIDDEN(false),
	RESOURCE_NOT_FOUND(false),
	PRECONDITION_REQUIRED(false),
	VERSION_CONFLICT(false),
	STATE_TRANSITION_NOT_ALLOWED(false),
	IDEMPOTENCY_KEY_REUSED(false),
	IDEMPOTENCY_IN_PROGRESS(true),
	ORDER_ALREADY_IN_ACTIVE_BATCH(false),
	ASSIGNMENT_CONFLICT(false),
	DRIVER_INELIGIBLE(false),
	RECOMMENDATION_EXPIRED(false),
	RECOMMENDATION_INVALIDATED(false),
	ZONE_UNRESOLVED(false),
	BATCH_LOCK_VALIDATION_FAILED(false),
	COMPLETION_PREREQUISITES_MISSING(false),
	POD_POLICY_NOT_SATISFIED(false),
	OTP_INVALID_OR_EXPIRED(false),
	OTP_LOCKED(true),
	EVIDENCE_INVALID(false),
	UPLOAD_FAILED(true),
	RATE_LIMITED(true),
	EXTERNAL_DEPENDENCY_UNAVAILABLE(true),
	UNEXPECTED_FAILURE(false);

	private final boolean retryable;

	ErrorCode(boolean retryable) {
		this.retryable = retryable;
	}

	public boolean retryable() {
		return retryable;
	}
}
