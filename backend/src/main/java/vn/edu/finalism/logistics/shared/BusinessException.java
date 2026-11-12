package vn.edu.finalism.logistics.shared;

import java.util.Map;

public class BusinessException extends RuntimeException {

	private final ErrorCode code;
	private final Map<String, Object> details;

	public BusinessException(ErrorCode code, String message) {
		this(code, message, Map.of());
	}

	public BusinessException(ErrorCode code, String message, Map<String, Object> details) {
		super(message);
		this.code = code;
		this.details = Map.copyOf(details);
	}

	public static BusinessException notFound(String what) {
		return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, what + " not found.");
	}

	public static BusinessException stateNotAllowed(String message) {
		return new BusinessException(ErrorCode.STATE_TRANSITION_NOT_ALLOWED, message);
	}

	public static BusinessException versionConflict(long currentVersion) {
		return new BusinessException(ErrorCode.VERSION_CONFLICT, "The resource was changed by someone else.",
				Map.of("currentVersion", currentVersion));
	}

	public static BusinessException validation(String field, String fieldCode, String message) {
		return new BusinessException(ErrorCode.VALIDATION_ERROR, message,
				Map.of("field", field, "fieldCode", fieldCode));
	}

	public ErrorCode code() {
		return code;
	}

	public Map<String, Object> details() {
		return details;
	}
}
