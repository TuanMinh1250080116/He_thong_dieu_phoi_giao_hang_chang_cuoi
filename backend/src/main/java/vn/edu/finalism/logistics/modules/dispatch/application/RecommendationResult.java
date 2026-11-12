package vn.edu.finalism.logistics.modules.dispatch.application;

import java.util.UUID;

import vn.edu.finalism.logistics.modules.dispatch.domain.Snapshot;

public record RecommendationResult(UUID decisionId, long decisionVersion, Snapshot snapshot) {
}
