package vn.edu.finalism.logistics.platform.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

@Component
public class RequestFingerprinter {

	private final ObjectMapper mapper;

	RequestFingerprinter(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	public byte[] fingerprint(String path, String ifMatch, Object body) {
		StringBuilder canonical = new StringBuilder();
		canonical.append(path).append('\n').append(ifMatch == null ? "" : ifMatch).append('\n');
		append(canonical, body == null ? null : mapper.convertValue(body, Object.class));
		try {
			return MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is always available", e);
		}
	}

	private void append(StringBuilder out, Object value) {
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> sorted = new TreeMap<>();
			map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
			out.append('{');
			boolean first = true;
			for (Map.Entry<String, Object> entry : sorted.entrySet()) {
				if (!first) {
					out.append(',');
				}
				first = false;
				out.append(mapper.writeValueAsString(entry.getKey())).append(':');
				append(out, entry.getValue());
			}
			out.append('}');
		}
		else if (value instanceof List<?> list) {
			out.append('[');
			for (int i = 0; i < list.size(); i++) {
				if (i > 0) {
					out.append(',');
				}
				append(out, list.get(i));
			}
			out.append(']');
		}
		else {
			out.append(mapper.writeValueAsString(value));
		}
	}
}
