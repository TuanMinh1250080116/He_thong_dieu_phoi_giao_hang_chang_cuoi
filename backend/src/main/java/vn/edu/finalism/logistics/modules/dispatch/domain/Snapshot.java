package vn.edu.finalism.logistics.modules.dispatch.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record Snapshot(
		UUID id,
		UUID decisionId,
		UUID batchId,
		long batchVersion,
		UUID ruleVersionId,
		int ruleVersionNo,
		String status,
		Instant generatedAt,
		Instant expiresAt,
		int candidateCount,
		int eligibleCount,
		Map<String, Integer> exclusionSummary,
		List<Candidate> candidates) {

	public String effectiveStatus(Instant now) {
		return "ACTIVE".equals(status) && !expiresAt.isAfter(now) ? "EXPIRED" : status;
	}

	public String outcome() {
		return eligibleCount == 0 ? "NO_ELIGIBLE_DRIVER" : "RANKED";
	}
}
