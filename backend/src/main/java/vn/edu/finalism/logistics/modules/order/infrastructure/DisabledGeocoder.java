package vn.edu.finalism.logistics.modules.order.infrastructure;

import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import vn.edu.finalism.logistics.modules.order.application.port.GeocodingPort;
import vn.edu.finalism.logistics.shared.GeoPoint;

@Component
@ConditionalOnProperty(name = "app.geocoding.enabled", havingValue = "false", matchIfMissing = true)
class DisabledGeocoder implements GeocodingPort {

	@Override
	public Optional<GeoPoint> geocode(String address) {
		return Optional.empty();
	}

	@Override
	public boolean enabled() {
		return false;
	}
}
