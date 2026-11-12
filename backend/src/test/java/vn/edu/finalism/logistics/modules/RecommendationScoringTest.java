package vn.edu.finalism.logistics.modules;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import vn.edu.finalism.logistics.modules.dispatch.domain.Candidate;
import vn.edu.finalism.logistics.modules.dispatch.domain.DriverEligibilityService.Requirements;
import vn.edu.finalism.logistics.modules.dispatch.domain.DriverFacts;
import vn.edu.finalism.logistics.modules.dispatch.domain.DriverRecommendationEngine;
import vn.edu.finalism.logistics.modules.dispatch.domain.DriverRecommendationEngine.Weights;

class RecommendationScoringTest {

	private static final Requirements NEED = new Requirements(10_000, 1_000_000);
	private static final int RADIUS = 20_000;

	private static Weights only(String distance, String capacity, String availability, String zone) {
		return new Weights(new BigDecimal(distance), new BigDecimal(capacity), new BigDecimal(availability),
				new BigDecimal(zone));
	}

	private static DriverFacts driver(UUID id, double distance, int weightCapacity, int recentAssignments,
			double zoneCoverage) {
		return new DriverFacts(id, UUID.randomUUID(), "Driver", true, "AVAILABLE", UUID.randomUUID(), "VAN", true,
				weightCapacity, 100_000_000L, true, Instant.now(), distance, false, recentAssignments, zoneCoverage);
	}

	private static Candidate single(DriverFacts driver, Weights weights) {
		return DriverRecommendationEngine.rank(List.of(driver), NEED, weights, RADIUS, 5).get(0);
	}

	@Test
	void distanceScoreFallsLinearlyToZeroAtTheSearchRadius() {
		Weights w = only("1", "0", "0", "0");

		assertThat(single(driver(UUID.randomUUID(), 0, 100_000, 0, 1), w).totalScore()).isEqualByComparingTo("100");
		assertThat(single(driver(UUID.randomUUID(), 10_000, 100_000, 0, 1), w).totalScore()).isEqualByComparingTo("50");
		assertThat(single(driver(UUID.randomUUID(), 20_000, 100_000, 0, 1), w).totalScore()).isEqualByComparingTo("0");
	}

	@Test
	void capacityFitRewardsATighterVehicle() {
		Weights w = only("0", "1", "0", "0");

		BigDecimal snug = single(driver(UUID.randomUUID(), 0, 20_000, 0, 1), w).totalScore();
		BigDecimal roomy = single(driver(UUID.randomUUID(), 0, 100_000, 0, 1), w).totalScore();

		assertThat(snug).isEqualByComparingTo("50");
		assertThat(roomy).isEqualByComparingTo("10");
	}

	@Test
	void availabilityDropsByAQuarterPerRecentAssignmentAndStopsAtZero() {
		Weights w = only("0", "0", "1", "0");

		assertThat(single(driver(UUID.randomUUID(), 0, 100_000, 0, 1), w).scoreAvailability()).isEqualByComparingTo("100");
		assertThat(single(driver(UUID.randomUUID(), 0, 100_000, 1, 1), w).scoreAvailability()).isEqualByComparingTo("75");
		assertThat(single(driver(UUID.randomUUID(), 0, 100_000, 3, 1), w).scoreAvailability()).isEqualByComparingTo("25");
		assertThat(single(driver(UUID.randomUUID(), 0, 100_000, 9, 1), w).scoreAvailability()).isEqualByComparingTo("0");
	}

	@Test
	void zoneCompatibilityFollowsTheShareOfPointsInsideTheDriversZones() {
		Weights w = only("0", "0", "0", "1");

		assertThat(single(driver(UUID.randomUUID(), 0, 100_000, 0, 1.0), w).scoreZoneCompat()).isEqualByComparingTo("100");
		assertThat(single(driver(UUID.randomUUID(), 0, 100_000, 0, 0.5), w).scoreZoneCompat()).isEqualByComparingTo("50");
		assertThat(single(driver(UUID.randomUUID(), 0, 100_000, 0, 0.0), w).scoreZoneCompat()).isEqualByComparingTo("0");
	}

	@Test
	void aBusierDriverLosesAnOtherwiseIdenticalRanking() {
		Weights w = only("0.25", "0.25", "0.25", "0.25");
		UUID idle = new UUID(0, 9);
		UUID busy = new UUID(0, 1);

		List<Candidate> ranked = DriverRecommendationEngine.rank(
				List.of(driver(busy, 1000, 100_000, 2, 1), driver(idle, 1000, 100_000, 0, 1)), NEED, w, RADIUS, 5);

		assertThat(ranked.get(0).driverId()).isEqualTo(idle);
		assertThat(ranked.get(0).totalScore()).isGreaterThan(ranked.get(1).totalScore());
	}

	@Test
	void identicalScoresFallBackToDistanceThenDriverId() {
		Weights w = only("0", "0", "1", "0");
		UUID lowId = new UUID(0, 1);
		UUID highId = new UUID(0, 2);
		UUID closest = new UUID(0, 3);

		List<Candidate> ranked = DriverRecommendationEngine.rank(
				List.of(driver(highId, 900, 100_000, 0, 1), driver(lowId, 900, 100_000, 0, 1),
						driver(closest, 100, 100_000, 0, 1)),
				NEED, w, RADIUS, 5);

		assertThat(ranked).extracting(Candidate::driverId).containsExactly(closest, lowId, highId);
	}

	@Test
	void anUnknownDistanceIsScoredAsTheFarthestPossible() {
		Weights w = only("1", "0", "0", "0");
		DriverFacts noDistance = new DriverFacts(UUID.randomUUID(), UUID.randomUUID(), "Driver", true, "AVAILABLE",
				UUID.randomUUID(), "VAN", true, 100_000, 100_000_000L, true, Instant.now(), null, false, 0, 1.0);

		assertThat(single(noDistance, w).totalScore()).isEqualByComparingTo("0");
	}
}
