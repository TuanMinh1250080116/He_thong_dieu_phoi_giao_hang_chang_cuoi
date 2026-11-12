package vn.edu.finalism.logistics.modules.dispatch.presentation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;
import vn.edu.finalism.logistics.modules.dispatch.application.DispatchBoardHandler;
import vn.edu.finalism.logistics.modules.dispatch.application.port.BoardReadPort.Panel;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.PageResult;

@RestController
@RequestMapping("/api/v1/dispatch")
class BoardController {

	private final DispatchBoardHandler board;
	private final ObjectMapper mapper;
	private final ClockPort clock;

	BoardController(DispatchBoardHandler board, ObjectMapper mapper, ClockPort clock) {
		this.board = board;
		this.mapper = mapper;
		this.clock = clock;
	}

	record Page(List<Map<String, Object>> items, String nextCursor, Instant asOf) {
	}

	@GetMapping("/board")
	ResponseEntity<Map<String, Object>> board(ActorContext actor, @RequestParam(required = false) UUID zoneId,
			@RequestParam(required = false) String q, @RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to,
			@RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
		Map<String, Panel> panels = board.board(actor, zoneId, q, from, to);
		String etag = "\"" + fingerprint(mapper.writeValueAsString(panels)) + "\"";
		CacheControl cache = CacheControl.noCache().cachePrivate();
		if (matches(ifNoneMatch, etag)) {
			return ResponseEntity.status(304).eTag(etag).cacheControl(cache).build();
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("panels", panels);
		body.put("asOf", clock.now());
		return ResponseEntity.ok().eTag(etag).cacheControl(cache).body(body);
	}

	@GetMapping("/batches")
	Page batches(ActorContext actor, @RequestParam(required = false) UUID zoneId,
			@RequestParam(required = false) String status, @RequestParam(required = false) String q,
			@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		PageResult<Map<String, Object>> page = board.batches(actor, zoneId, status, q, from, to, cursor, limit);
		return new Page(page.items(), page.nextCursor(), page.asOf());
	}

	@GetMapping("/deliveries")
	Page deliveries(ActorContext actor, @RequestParam(required = false) UUID zoneId,
			@RequestParam(required = false) String status, @RequestParam(required = false) String q,
			@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		PageResult<Map<String, Object>> page = board.deliveries(actor, zoneId, status, q, from, to, cursor, limit);
		return new Page(page.items(), page.nextCursor(), page.asOf());
	}

	private static boolean matches(String header, String etag) {
		if (header == null || header.isBlank()) {
			return false;
		}
		for (String candidate : header.split(",")) {
			String trimmed = candidate.trim();
			if (trimmed.startsWith("W/")) {
				trimmed = trimmed.substring(2);
			}
			if (trimmed.equals(etag) || "*".equals(trimmed)) {
				return true;
			}
		}
		return false;
	}

	private static String fingerprint(String json) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 12);
		}
		catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is always available", e);
		}
	}
}
