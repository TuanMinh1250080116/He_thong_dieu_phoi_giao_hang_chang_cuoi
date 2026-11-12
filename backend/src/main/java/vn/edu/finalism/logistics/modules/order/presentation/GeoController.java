package vn.edu.finalism.logistics.modules.order.presentation;

import java.time.Duration;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.edu.finalism.logistics.modules.order.application.port.GeocodingPort;
import vn.edu.finalism.logistics.platform.ratelimit.RateLimiter;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ErrorCode;
import vn.edu.finalism.logistics.shared.GeoPoint;

@RestController
class GeoController {

	private final GeocodingPort geocoder;
	private final RateLimiter limiter;

	GeoController(GeocodingPort geocoder, RateLimiter limiter) {
		this.geocoder = geocoder;
		this.limiter = limiter;
	}

	@GetMapping("/api/v1/geo/geocode")
	Map<String, Object> geocode(ActorContext actor, @RequestParam String q) {
		if (!actor.hasPermission("order.create") && !actor.hasPermission("dispatch.batch.manage")
				&& !actor.hasPermission("config.zone.manage")) {
			throw new BusinessException(ErrorCode.FORBIDDEN, "You do not have permission for this action.");
		}
		String query = q == null ? "" : q.trim();
		if (query.length() < 3 || query.length() > 200) {
			throw BusinessException.validation("q", "INVALID", "Type between 3 and 200 characters.");
		}
		if (!geocoder.enabled()) {
			throw new BusinessException(ErrorCode.EXTERNAL_DEPENDENCY_UNAVAILABLE,
					"Address search is not enabled on this server.", Map.of());
		}
		limiter.check("geocode:" + actor.userId(), 20, Duration.ofMinutes(1));
		GeoPoint point = geocoder.geocode(query).orElseThrow(() -> BusinessException.notFound("Address"));
		return Map.of("lat", point.lat(), "lng", point.lng());
	}
}
