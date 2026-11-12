package vn.edu.finalism.logistics.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import vn.edu.finalism.logistics.modules.dispatch.domain.Candidate;
import vn.edu.finalism.logistics.modules.dispatch.domain.DriverEligibilityService;
import vn.edu.finalism.logistics.modules.dispatch.domain.DriverEligibilityService.Requirements;
import vn.edu.finalism.logistics.modules.dispatch.domain.DriverFacts;
import vn.edu.finalism.logistics.modules.dispatch.domain.DriverRecommendationEngine;
import vn.edu.finalism.logistics.modules.dispatch.domain.DriverRecommendationEngine.Weights;
import vn.edu.finalism.logistics.modules.order.domain.Order;
import vn.edu.finalism.logistics.modules.order.domain.OrderStatus;
import vn.edu.finalism.logistics.modules.proof.domain.PodRequirementPolicy;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ErrorCode;
import vn.edu.finalism.logistics.shared.GeoPoint;

class SliceOneDomainTest {

	private static final Instant NOW = Instant.parse("2026-11-02T08:00:00Z");
	private static final Requirements NEED = new Requirements(10_000, 1_000_000);

	private static DriverFacts driver(UUID id, String availability, boolean vehicleActive, int capacityWeight,
			Instant located, double distance, boolean conflict, boolean servesZone) {
		return new DriverFacts(id, UUID.randomUUID(), "Driver", true, availability, UUID.randomUUID(), "VAN",
				vehicleActive, capacityWeight, 100_000_000L, servesZone, located, distance, conflict, 0, 1.0);
	}

	private static DriverFacts goodDriver(UUID id, double distance) {
		return driver(id, "AVAILABLE", true, 100_000, NOW.minusSeconds(30), distance, false, true);
	}

	@Test
	void eligibleDriverHasNoReasons() {
		assertThat(DriverEligibilityService.reasonsAgainst(goodDriver(UUID.randomUUID(), 1000), NEED, NOW, 600)).isEmpty();
	}

	@Test
	void everyHardConstraintProducesItsOwnReason() {
		DriverFacts bad = driver(UUID.randomUUID(), "BUSY", true, 5_000, NOW.minusSeconds(3600), 1000, true, false);

		assertThat(DriverEligibilityService.reasonsAgainst(bad, NEED, NOW, 600)).containsExactlyInAnyOrder(
				DriverEligibilityService.NOT_AVAILABLE, DriverEligibilityService.VEHICLE_CAPACITY,
				DriverEligibilityService.ASSIGNMENT_CONFLICT, DriverEligibilityService.ZONE_INCOMPATIBLE,
				DriverEligibilityService.LOCATION_STALE);
	}

	@Test
	void inactiveVehicleIsReportedWithoutCapacityNoise() {
		DriverFacts noVehicle = driver(UUID.randomUUID(), "AVAILABLE", false, 100_000, NOW, 10, false, true);

		assertThat(DriverEligibilityService.reasonsAgainst(noVehicle, NEED, NOW, 600))
				.containsExactly(DriverEligibilityService.VEHICLE_INACTIVE);
	}

	@Test
	void closerDriverRanksFirstAndTieBreaksAreStable() {
		Weights weights = new Weights(new BigDecimal("0.4"), new BigDecimal("0.25"), new BigDecimal("0.15"),
				new BigDecimal("0.2"));
		UUID low = new UUID(0, 1);
		UUID high = new UUID(0, 2);
		DriverFacts near = goodDriver(high, 500);
		DriverFacts farLowId = goodDriver(low, 4000);
		DriverFacts farHighId = goodDriver(high, 4000);

		List<Candidate> ranked = DriverRecommendationEngine.rank(List.of(farHighId, near, farLowId), NEED, weights,
				20_000, 50);

		assertThat(ranked).extracting(Candidate::rank).containsExactly(1, 2, 3);
		assertThat(ranked.get(0).distanceM()).isEqualTo(500);
		assertThat(ranked.get(1).driverId()).isEqualTo(low);
		assertThat(ranked.get(0).totalScore()).isGreaterThan(ranked.get(1).totalScore());
	}

	@Test
	void scoresStayBetweenZeroAndOneHundredAndTopNIsRespected() {
		Weights weights = new Weights(new BigDecimal("0.4"), new BigDecimal("0.25"), new BigDecimal("0.15"),
				new BigDecimal("0.2"));
		List<DriverFacts> drivers = List.of(goodDriver(UUID.randomUUID(), 0), goodDriver(UUID.randomUUID(), 50_000),
				goodDriver(UUID.randomUUID(), 10_000));

		List<Candidate> ranked = DriverRecommendationEngine.rank(drivers, NEED, weights, 20_000, 2);

		assertThat(ranked).hasSize(2);
		ranked.forEach(c -> assertThat(c.totalScore()).isBetween(BigDecimal.ZERO, new BigDecimal("100")));
	}

	@Test
	void anyOfNeedsOneKindAndAllOfNeedsEveryKind() {
		Map<String, Object> anyOf = Map.of("op", "ANY_OF", "items", List.of("OTP", "PHOTO", "SIGNATURE"));
		Map<String, Object> allOf = Map.of("op", "ALL_OF", "items", List.of("OTP", "SIGNATURE"));

		assertThat(PodRequirementPolicy.missingEvidence(anyOf, Set.of("PHOTO"))).isEmpty();
		assertThat(PodRequirementPolicy.missingEvidence(anyOf, Set.of())).containsExactly("OTP", "PHOTO", "SIGNATURE");
		assertThat(PodRequirementPolicy.missingEvidence(allOf, Set.of("PHOTO"))).containsExactly("OTP", "SIGNATURE");
		assertThat(PodRequirementPolicy.missingEvidence(allOf, Set.of("OTP", "SIGNATURE"))).isEmpty();
	}

	@Test
	void recipientNameRuleDependsOnHandoff() {
		assertThat(PodRequirementPolicy.recipientNameRequired("DIRECT_HANDOFF", true)).isTrue();
		assertThat(PodRequirementPolicy.recipientNameRequired("DIRECT_HANDOFF", false)).isFalse();
		assertThat(PodRequirementPolicy.recipientNameRequired("ALWAYS", false)).isTrue();
		assertThat(PodRequirementPolicy.recipientNameRequired("NEVER", true)).isFalse();
	}

	private static Order draftOrder() {
		return Order.create(UUID.randomUUID(), "ORD-1", UUID.randomUUID(), "A", new GeoPoint(10.7, 106.7), "B",
				new GeoPoint(10.8, 106.7), "Recipient", null, 1000, 10, 10, 10, null, NOW, NOW.plusSeconds(3600))
				.withZone(UUID.randomUUID(), UUID.randomUUID());
	}

	@Test
	void orderFollowsTheHappyPathAndNeverGoesBack() {
		Order delivered = draftOrder().markReady().attachToBatch().markAssigned().markInDelivery().markDelivered();

		assertThat(delivered.status()).isEqualTo(OrderStatus.DELIVERED);
		assertThatThrownBy(delivered::attachToBatch).isInstanceOf(BusinessException.class);
		assertThatThrownBy(delivered::markInDelivery).isInstanceOf(BusinessException.class);
	}

	@Test
	void orderCannotSkipStates() {
		Order ready = draftOrder().markReady();

		assertThatThrownBy(ready::markAssigned).isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.code()).isEqualTo(ErrorCode.STATE_TRANSITION_NOT_ALLOWED));
		assertThatThrownBy(ready::markDelivered).isInstanceOf(BusinessException.class);
	}

	@Test
	void submittingNeedsZonePointsAndDimensions() {
		Order withoutZone = Order.create(UUID.randomUUID(), "ORD-2", UUID.randomUUID(), "A", new GeoPoint(10.7, 106.7),
				"B", new GeoPoint(10.8, 106.7), "R", null, 1000, 10, 10, 10, null, NOW, NOW.plusSeconds(3600));
		Order withoutDimensions = Order.create(UUID.randomUUID(), "ORD-3", UUID.randomUUID(), "A",
				new GeoPoint(10.7, 106.7), "B", new GeoPoint(10.8, 106.7), "R", null, 1000, null, null, null, null,
				NOW, NOW.plusSeconds(3600)).withZone(UUID.randomUUID(), UUID.randomUUID());

		assertThatThrownBy(withoutZone::markReady).isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.code()).isEqualTo(ErrorCode.ZONE_UNRESOLVED));
		assertThatThrownBy(withoutDimensions::markReady).isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void orderRejectsInvalidWindowAndWeight() {
		assertThatThrownBy(() -> Order.create(UUID.randomUUID(), "ORD-4", UUID.randomUUID(), "A", null, "B", null, "R",
				null, 1000, null, null, null, null, NOW, NOW)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> Order.create(UUID.randomUUID(), "ORD-5", UUID.randomUUID(), "A", null, "B", null, "R",
				null, 0, null, null, null, null, NOW, NOW.plusSeconds(60))).isInstanceOf(BusinessException.class);
	}
}
