package vn.edu.finalism.logistics.modules.dispatch.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

import vn.edu.finalism.logistics.modules.dispatch.domain.DriverEligibilityService.Requirements;

public final class DriverRecommendationEngine {

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
	private static final BigDecimal WORKLOAD_PENALTY = BigDecimal.valueOf(25);

	private DriverRecommendationEngine() {
	}

	public record Weights(BigDecimal distance, BigDecimal capacityFit, BigDecimal availability, BigDecimal zoneCompat) {
	}

	private record Scored(DriverFacts driver, double distanceM, BigDecimal distance, BigDecimal capacityFit,
			BigDecimal availability, BigDecimal zoneCompat, BigDecimal total) {
	}

	public static List<Candidate> rank(List<DriverFacts> eligible, Requirements need, Weights weights,
			int searchRadiusM, int topN) {
		List<Scored> scored = eligible.stream().map(d -> score(d, need, weights, searchRadiusM))
				.sorted(Comparator.comparing(Scored::total).reversed()
						.thenComparingDouble(Scored::distanceM)
						.thenComparing(s -> s.driver().driverId()))
				.limit(topN)
				.toList();
		java.util.ArrayList<Candidate> ranked = new java.util.ArrayList<>();
		for (int i = 0; i < scored.size(); i++) {
			Scored s = scored.get(i);
			DriverFacts d = s.driver();
			ranked.add(new Candidate(i + 1, d.driverId(), d.fullName(), d.vehicleId(), d.vehicleType(), s.total(),
					s.distanceM(), s.distance(), s.capacityFit(), s.availability(), s.zoneCompat(),
					d.capacityWeightG(), d.capacityVolumeCm3()));
		}
		return ranked;
	}

	private static Scored score(DriverFacts driver, Requirements need, Weights w, int searchRadiusM) {
		double distanceM = driver.distanceM() == null ? searchRadiusM : driver.distanceM();
		BigDecimal distanceScore = clamp(HUNDRED.multiply(BigDecimal.valueOf(1 - distanceM / searchRadiusM)));
		BigDecimal weightRatio = BigDecimal.valueOf((double) need.weightG() / driver.capacityWeightG());
		BigDecimal volumeRatio = BigDecimal.valueOf((double) need.volumeCm3() / driver.capacityVolumeCm3());
		BigDecimal capacityFit = clamp(HUNDRED.multiply(weightRatio.max(volumeRatio)));
		BigDecimal availability = clamp(HUNDRED.subtract(WORKLOAD_PENALTY.multiply(
				BigDecimal.valueOf(driver.recentAssignments()))));
		BigDecimal zoneCompat = clamp(HUNDRED.multiply(BigDecimal.valueOf(driver.zoneCoverage())));
		BigDecimal total = distanceScore.multiply(w.distance())
				.add(capacityFit.multiply(w.capacityFit()))
				.add(availability.multiply(w.availability()))
				.add(zoneCompat.multiply(w.zoneCompat()));
		return new Scored(driver, distanceM, scale(distanceScore), scale(capacityFit), scale(availability),
				scale(zoneCompat), scale(clamp(total)));
	}

	private static BigDecimal clamp(BigDecimal value) {
		if (value.signum() < 0) {
			return BigDecimal.ZERO;
		}
		return value.compareTo(HUNDRED) > 0 ? HUNDRED : value;
	}

	private static BigDecimal scale(BigDecimal value) {
		return value.setScale(3, RoundingMode.HALF_UP);
	}
}
