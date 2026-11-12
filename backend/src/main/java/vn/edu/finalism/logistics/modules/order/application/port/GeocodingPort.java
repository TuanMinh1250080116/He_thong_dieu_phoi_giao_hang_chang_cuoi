package vn.edu.finalism.logistics.modules.order.application.port;

import java.util.Optional;

import vn.edu.finalism.logistics.shared.GeoPoint;

public interface GeocodingPort {

	Optional<GeoPoint> geocode(String address);

	default boolean enabled() {
		return true;
	}
}
