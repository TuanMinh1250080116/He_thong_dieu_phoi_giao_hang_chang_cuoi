package vn.edu.finalism.logistics.modules.audit.application;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class AuditSanitizer {

	public static final String REDACTED = "[REDACTED]";

	private static final Set<String> FORBIDDEN_KEYS = Set.of(
			"password", "passwordhash", "secret", "token", "accesstoken", "refreshtoken", "tokenhash",
			"authorization", "otp", "otpcode", "codehmac", "signedurl");

	private AuditSanitizer() {
	}

	public static Map<String, Object> sanitize(Map<String, ?> input) {
		if (input == null) {
			return null;
		}
		Map<String, Object> result = new LinkedHashMap<>();
		input.forEach((key, value) -> result.put(key,
				isForbidden(key) ? REDACTED : sanitizeValue(value)));
		return result;
	}

	@SuppressWarnings("unchecked")
	private static Object sanitizeValue(Object value) {
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> converted = new LinkedHashMap<>();
			map.forEach((k, v) -> converted.put(String.valueOf(k), v));
			return sanitize(converted);
		}
		if (value instanceof Collection<?> collection) {
			return List.copyOf(collection.stream().map(AuditSanitizer::sanitizeValue).toList());
		}
		return value;
	}

	private static boolean isForbidden(String key) {
		String normalized = key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
		return FORBIDDEN_KEYS.contains(normalized);
	}
}
