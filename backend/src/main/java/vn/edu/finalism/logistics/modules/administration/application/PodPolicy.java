package vn.edu.finalism.logistics.modules.administration.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record PodPolicy(
		UUID id,
		int versionNo,
		String source,
		boolean requiresGps,
		Integer maxGpsAccuracyM,
		Map<String, Object> evidenceExpression,
		String recipientNameRule,
		Instant effectiveFrom) {

	public Map<String, Object> toSnapshot() {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("policyVersionId", id.toString());
		snapshot.put("policyVersion", versionNo);
		snapshot.put("source", source);
		snapshot.put("requiresGps", requiresGps);
		snapshot.put("maxGpsAccuracyM", maxGpsAccuracyM);
		snapshot.put("evidenceExpression", evidenceExpression);
		snapshot.put("recipientNameRule", recipientNameRule);
		snapshot.put("effectiveAt", effectiveFrom.toString());
		return snapshot;
	}
}
