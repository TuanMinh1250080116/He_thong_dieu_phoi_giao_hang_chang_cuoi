package vn.edu.finalism.logistics.modules.order.infrastructure;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.edu.finalism.logistics.modules.order.application.port.GeocodingPort;
import vn.edu.finalism.logistics.shared.GeoPoint;

@Component
@ConditionalOnProperty(name = "app.geocoding.enabled", havingValue = "true")
class NominatimGeocoder implements GeocodingPort {

	private static final Logger log = LoggerFactory.getLogger(NominatimGeocoder.class);

	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
	private final ObjectMapper mapper;
	private final String baseUrl;
	private final String userAgent;

	NominatimGeocoder(ObjectMapper mapper,
			@Value("${app.geocoding.url:https://nominatim.openstreetmap.org}") String baseUrl,
			@Value("${app.geocoding.user-agent:logistics-dispatch-student-project}") String userAgent) {
		this.mapper = mapper;
		this.baseUrl = baseUrl;
		this.userAgent = userAgent;
	}

	@Override
	public Optional<GeoPoint> geocode(String address) {
		if (address == null || address.isBlank()) {
			return Optional.empty();
		}
		try {
			URI uri = URI.create(baseUrl + "/search?format=jsonv2&limit=1&countrycodes=vn&q="
					+ URLEncoder.encode(address.trim(), StandardCharsets.UTF_8));
			HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(4))
					.header("User-Agent", userAgent).header("Accept", "application/json").GET().build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				return Optional.empty();
			}
			JsonNode first = mapper.readTree(response.body()).path(0);
			if (first.isMissingNode() || first.path("lat").isMissingNode() || first.path("lon").isMissingNode()) {
				return Optional.empty();
			}
			return Optional.of(new GeoPoint(Double.parseDouble(first.path("lat").asString()),
					Double.parseDouble(first.path("lon").asString())));
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
		catch (RuntimeException | java.io.IOException e) {
			log.warn("Geocoding failed: {}", e.getClass().getSimpleName());
			return Optional.empty();
		}
	}
}
