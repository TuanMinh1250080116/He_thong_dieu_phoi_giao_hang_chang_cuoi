package vn.edu.finalism.logistics.modules.dispatch.application;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.dispatch.application.port.BatchRepository;
import vn.edu.finalism.logistics.modules.dispatch.application.port.DispatchRepository;
import vn.edu.finalism.logistics.modules.dispatch.domain.DecisionStatus;
import vn.edu.finalism.logistics.modules.dispatch.domain.DeliveryBatch;
import vn.edu.finalism.logistics.modules.dispatch.domain.DispatchDecision;
import vn.edu.finalism.logistics.modules.dispatch.domain.Snapshot;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;

@Service
public class RecommendationQueryHandler {

	private final BatchRepository batches;
	private final DispatchRepository dispatch;

	RecommendationQueryHandler(BatchRepository batches, DispatchRepository dispatch) {
		this.batches = batches;
		this.dispatch = dispatch;
	}

	public record DecisionView(UUID decisionId, DecisionStatus status, long version,
			Optional<DispatchRepository.AssignmentSummary> assignment) {
	}

	@Transactional(readOnly = true)
	public DecisionView decision(ActorContext actor, UUID batchId) {
		actor.requirePermission("dispatch.recommendation.generate");
		DeliveryBatch batch = batches.findById(batchId).orElseThrow(() -> BusinessException.notFound("Batch"));
		actor.requireScope(batch.zoneId());
		DispatchDecision decision = dispatch.findDecisionByBatch(batchId)
				.orElseThrow(() -> BusinessException.notFound("Decision"));
		return new DecisionView(decision.id(), decision.status(), decision.version(),
				dispatch.latestAssignmentSummary(decision.id()));
	}

	@Transactional(readOnly = true)
	public RecommendationResult current(ActorContext actor, UUID batchId) {
		actor.requirePermission("dispatch.recommendation.generate");
		DeliveryBatch batch = batches.findById(batchId).orElseThrow(() -> BusinessException.notFound("Batch"));
		actor.requireScope(batch.zoneId());
		DispatchDecision decision = dispatch.findDecisionByBatch(batchId)
				.orElseThrow(() -> BusinessException.notFound("Recommendation"));
		Snapshot snapshot = dispatch.findLatestSnapshot(decision.id())
				.orElseThrow(() -> BusinessException.notFound("Recommendation"));
		return new RecommendationResult(decision.id(), decision.version(), snapshot);
	}
}
