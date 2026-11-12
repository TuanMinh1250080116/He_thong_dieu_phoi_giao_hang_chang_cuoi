package vn.edu.finalism.logistics.modules.administration.domain;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import vn.edu.finalism.logistics.shared.BusinessException;

public final class ConfigValidation {

	public static final Set<String> EVIDENCE_KINDS = Set.of("OTP", "PHOTO", "SIGNATURE");
	public static final Set<String> EVIDENCE_OPS = Set.of("ANY_OF", "ALL_OF");
	public static final Set<String> RECIPIENT_RULES = Set.of("ALWAYS", "DIRECT_HANDOFF", "NEVER");

	private ConfigValidation() {
	}

	public static void validateRule(String name, BigDecimal distance, BigDecimal capacityFit, BigDecimal availability,
			BigDecimal zoneCompat, Integer ttlSeconds, Integer searchRadiusM, Integer maxLocationAgeSeconds,
			Integer persistTopN) {
		requireName(name);
		requireWeight("weights.distance", distance);
		requireWeight("weights.capacityFit", capacityFit);
		requireWeight("weights.availability", availability);
		requireWeight("weights.zoneCompat", zoneCompat);
		if (distance.add(capacityFit).add(availability).add(zoneCompat).compareTo(BigDecimal.ONE) != 0) {
			throw BusinessException.validation("weights", "SUM_NOT_ONE", "The four weights must add up to exactly 1.");
		}
		requireRange("ttlSeconds", ttlSeconds, 60, 900);
		requireRange("searchRadiusM", searchRadiusM, 1, 200_000);
		requireRange("maxLocationAgeSeconds", maxLocationAgeSeconds, 1, 86_400);
		requireRange("persistTopN", persistTopN, 1, 200);
	}

	public static void validatePodPolicy(String name, String op, List<String> items, Integer maxGpsAccuracyM,
			String recipientNameRule) {
		requireName(name);
		if (op == null || !EVIDENCE_OPS.contains(op)) {
			throw BusinessException.validation("evidenceExpression.op", "INVALID", "op must be ANY_OF or ALL_OF.");
		}
		if (items == null || items.isEmpty() || items.size() > EVIDENCE_KINDS.size()) {
			throw BusinessException.validation("evidenceExpression.items", "INVALID",
					"Give one to three evidence kinds.");
		}
		Set<String> seen = new HashSet<>();
		for (String item : items) {
			if (!EVIDENCE_KINDS.contains(item) || !seen.add(item)) {
				throw BusinessException.validation("evidenceExpression.items", "INVALID",
						"Evidence kinds must be distinct values of OTP, PHOTO or SIGNATURE.");
			}
		}
		if (maxGpsAccuracyM != null) {
			requireRange("maxGpsAccuracyM", maxGpsAccuracyM, 1, 1000);
		}
		if (recipientNameRule == null || !RECIPIENT_RULES.contains(recipientNameRule)) {
			throw BusinessException.validation("recipientNameRule", "INVALID",
					"recipientNameRule must be ALWAYS, DIRECT_HANDOFF or NEVER.");
		}
	}

	public static void validateZoneCode(String code) {
		if (code == null || !code.matches("^[A-Z0-9][A-Z0-9_-]{1,31}$")) {
			throw BusinessException.validation("code", "INVALID",
					"The code needs 2 to 32 characters: capital letters, digits, dash or underscore.");
		}
	}

	private static void requireName(String name) {
		if (name == null || name.isBlank() || name.length() > 120) {
			throw BusinessException.validation("name", "INVALID", "A name of up to 120 characters is required.");
		}
	}

	private static void requireWeight(String field, BigDecimal weight) {
		if (weight == null || weight.signum() < 0 || weight.compareTo(BigDecimal.ONE) > 0
				|| weight.stripTrailingZeros().scale() > 4) {
			throw BusinessException.validation(field, "INVALID",
					"Each weight must be between 0 and 1 with at most 4 decimals.");
		}
	}

	private static void requireRange(String field, Integer value, int min, int max) {
		if (value == null || value < min || value > max) {
			throw BusinessException.validation(field, "OUT_OF_RANGE", field + " must be between " + min + " and " + max + ".");
		}
	}
}
