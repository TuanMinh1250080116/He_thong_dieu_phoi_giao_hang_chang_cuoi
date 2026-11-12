package vn.edu.finalism.logistics.shared;

import java.util.regex.Pattern;

public record IdempotencyKey(String value) {

	public static final String HEADER = "Idempotency-Key";

	private static final Pattern PRINTABLE = Pattern.compile("[\\x21-\\x7E]+");

	public IdempotencyKey {
		if (value == null || value.isBlank()) {
			throw BusinessException.validation(HEADER, "REQUIRED", "Idempotency-Key header is required.");
		}
		if (value.length() < 8 || value.length() > 128 || !PRINTABLE.matcher(value).matches()) {
			throw BusinessException.validation(HEADER, "INVALID",
					"Idempotency-Key must be 8 to 128 printable characters without spaces.");
		}
	}

	public static IdempotencyKey fromHeader(String headerValue) {
		return new IdempotencyKey(headerValue);
	}
}
