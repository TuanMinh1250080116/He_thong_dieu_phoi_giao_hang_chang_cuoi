package vn.edu.finalism.logistics.modules.administration.application;

import java.util.Optional;
import java.util.UUID;

import vn.edu.finalism.logistics.shared.GeoPoint;

public interface ZoneLookupPort {

	Optional<ZoneMatch> findZone(GeoPoint point);

	record ZoneMatch(UUID zoneId, UUID zoneVersionId) {
	}
}
