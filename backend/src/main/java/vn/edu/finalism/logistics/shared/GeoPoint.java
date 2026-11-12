package vn.edu.finalism.logistics.shared;

public record GeoPoint(double lat, double lng) {

	public GeoPoint {
		if (Double.isNaN(lat) || lat < -90 || lat > 90) {
			throw BusinessException.validation("point.lat", "OUT_OF_RANGE", "Latitude must be between -90 and 90.");
		}
		if (Double.isNaN(lng) || lng < -180 || lng > 180) {
			throw BusinessException.validation("point.lng", "OUT_OF_RANGE", "Longitude must be between -180 and 180.");
		}
	}
}
