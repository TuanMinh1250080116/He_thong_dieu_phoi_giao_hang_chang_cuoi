package vn.edu.finalism.logistics.shared;

public final class IfMatch {

	public static final String HEADER = "If-Match";

	private IfMatch() {
	}

	public static long parse(String headerValue) {
		if (headerValue == null || headerValue.isBlank()) {
			throw new BusinessException(ErrorCode.PRECONDITION_REQUIRED, "If-Match header is required.");
		}
		String cleaned = headerValue.trim();
		if (cleaned.startsWith("W/")) {
			cleaned = cleaned.substring(2);
		}
		cleaned = cleaned.replace("\"", "");
		try {
			long version = Long.parseLong(cleaned);
			if (version < 0) {
				throw new NumberFormatException();
			}
			return version;
		}
		catch (NumberFormatException e) {
			throw BusinessException.validation(HEADER, "INVALID", "If-Match must carry a version number.");
		}
	}

	public static String etag(long version) {
		return "\"" + version + "\"";
	}
}
