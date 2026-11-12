package vn.edu.finalism.logistics.shared;

import java.util.Map;
import java.util.Set;

public sealed interface UseCaseResult {

	record Success<T>(T data, long version, Set<String> capabilities) implements UseCaseResult {
	}

	record Rejected(ErrorCode code, Map<String, Object> safeDetails) implements UseCaseResult {
	}
}
