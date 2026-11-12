package vn.edu.finalism.logistics.shared;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public record PageCursor(Instant sortKey, UUID id) {

	public static final int DEFAULT_LIMIT = 25;
	public static final int MAX_LIMIT = 100;

	public String encode() {
		String raw = sortKey.toEpochMilli() + "|" + id;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	public static PageCursor decode(String cursor) {
		try {
			String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			String[] parts = raw.split("\\|");
			return new PageCursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), UUID.fromString(parts[1]));
		}
		catch (RuntimeException e) {
			throw BusinessException.validation("cursor", "INVALID", "The cursor is not valid for this request.");
		}
	}

	public static int normalizeLimit(Integer requested) {
		if (requested == null) {
			return DEFAULT_LIMIT;
		}
		if (requested < 1 || requested > MAX_LIMIT) {
			throw BusinessException.validation("limit", "OUT_OF_RANGE", "limit must be between 1 and 100.");
		}
		return requested;
	}
}
