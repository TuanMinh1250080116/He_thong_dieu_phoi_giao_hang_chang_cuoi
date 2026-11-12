package vn.edu.finalism.logistics.modules.dispatch.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.administration.application.DispatchRule;
import vn.edu.finalism.logistics.modules.administration.application.EffectiveRulePort;
import vn.edu.finalism.logistics.modules.audit.application.AuditAction;
import vn.edu.finalism.logistics.modules.audit.application.AuditEntry;
import vn.edu.finalism.logistics.modules.audit.application.AuditWriter;
import vn.edu.finalism.logistics.modules.dispatch.application.port.BatchRepository;
import vn.edu.finalism.logistics.modules.dispatch.application.port.DispatchRepository;
import vn.edu.finalism.logistics.modules.dispatch.application.port.DriverCandidateReadPort;
import vn.edu.finalism.logistics.modules.dispatch.domain.Candidate;
import vn.edu.finalism.logistics.modules.dispatch.domain.DecisionStatus;
import vn.edu.finalism.logistics.modules.dispatch.domain.DeliveryBatch;
import vn.edu.finalism.logistics.modules.dispatch.domain.DispatchDecision;
import vn.edu.finalism.logistics.modules.dispatch.domain.DriverEligibilityService;
import vn.edu.finalism.logistics.modules.dispatch.domain.DriverEligibilityService.Requirements;
import vn.edu.finalism.logistics.modules.dispatch.domain.DriverFacts;
import vn.edu.finalism.logistics.modules.dispatch.domain.DriverRecommendationEngine;
import vn.edu.finalism.logistics.modules.dispatch.domain.DriverRecommendationEngine.Weights;
import vn.edu.finalism.logistics.modules.dispatch.domain.Snapshot;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.GeoPoint;
import vn.edu.finalism.logistics.shared.event.EventEnvelope;
import vn.edu.finalism.logistics.shared.event.EventPublicationPort;

@Service
public class GenerateRecommendationsHandler {

	private final BatchRepository batches;
	private final DispatchRepository dispatch;
	private final DriverCandidateReadPort candidates;
	private final EffectiveRulePort rules;
	private final AuditWriter audit;
	private final EventPublicationPort events;
	private final ClockPort clock;

	GenerateRecommendationsHandler(BatchRepository batches, DispatchRepository dispatch,
			DriverCandidateReadPort candidates, EffectiveRulePort rules, AuditWriter audit,
			EventPublicationPort events, ClockPort clock) {
		this.batches = batches;
		this.dispatch = dispatch;
		this.candidates = candidates;
		this.rules = rules;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	public RecommendationResult handle(ActorContext actor, UUID batchId, long expectedBatchVersion) {
		actor.requirePermission("dispatch.recommendation.generate");
		DeliveryBatch batch = batches.findByIdForUpdate(batchId)
				.orElseThrow(() -> BusinessException.notFound("Batch"));
		actor.requireScope(batch.zoneId());
		if (batch.version() != expectedBatchVersion) {
			throw BusinessException.versionConflict(batch.version());
		}
		if (!batch.isLocked()) {
			throw BusinessException.stateNotAllowed("Recommendations need a locked batch.");
		}

		Instant now = clock.now();
		DispatchRule rule = rules.effectiveRule(batch.zoneId());
		Requirements need = new Requirements(batch.totalWeightG(), batch.totalVolumeCm3());
		GeoPoint pickup = candidates.pickupCentroid(batchId);
		List<DriverFacts> loaded = candidates.loadCandidates(batchId, batch.zoneId(), pickup, rule.searchRadiusM(),
				batch.plannedStart(), batch.plannedEnd());

		Map<String, Integer> exclusion = new TreeMap<>();
		List<DriverFacts> eligible = new ArrayList<>();
		for (DriverFacts driver : loaded) {
			List<String> reasons = DriverEligibilityService.reasonsAgainst(driver, need, now,
					rule.maxLocationAgeSeconds());
			if (reasons.isEmpty()) {
				eligible.add(driver);
			}
			else {
				reasons.forEach(reason -> exclusion.merge(reason, 1, Integer::sum));
			}
		}
		List<Candidate> ranked = DriverRecommendationEngine.rank(eligible, need,
				new Weights(rule.weightDistance(), rule.weightCapacityFit(), rule.weightAvailability(),
						rule.weightZoneCompat()),
				rule.searchRadiusM(), rule.persistTopN());

		DispatchDecision decision = dispatch.findOpenDecisionByBatchForUpdate(batchId).orElseGet(() -> {
			DispatchDecision created = DispatchDecision.open(UUID.randomUUID(), batchId, actor.userId());
			dispatch.insertDecision(created, now);
			return created;
		});
		if (decision.status() != DecisionStatus.PENDING_DECISION) {
			throw BusinessException.stateNotAllowed("This batch already has a driver decision.");
		}

		dispatch.closeActiveSnapshots(decision.id(), now);
		Snapshot snapshot = new Snapshot(UUID.randomUUID(), decision.id(), batchId, batch.version(), rule.id(),
				rule.versionNo(), "ACTIVE", now, now.plusSeconds(rule.ttlSeconds()), loaded.size(), eligible.size(),
				exclusion, ranked);
		dispatch.insertSnapshot(snapshot, actor.userId(), rule.persistTopN());
		if (!dispatch.updateDecision(decision.id(), decision.status(), decision.version(), now)) {
			throw BusinessException.versionConflict(decision.version() + 1);
		}

		audit.append(AuditEntry.forUser(actor, AuditAction.RECOMMENDATION_GENERATED, "BATCH", batchId,
				batch.zoneId(), null,
				Map.of("snapshotId", snapshot.id().toString(), "ruleVersion", rule.versionNo(),
						"candidateCount", loaded.size(), "eligibleCount", eligible.size(),
						"outcome", snapshot.outcome()),
				null, null));
		events.append(new EventEnvelope(UUID.randomUUID(), "DriverRecommended", 1, "DispatchDecision",
				decision.id(), now, actor.userId(), actor.correlationId(),
				Map.of("decisionId", decision.id().toString(), "snapshotId", snapshot.id().toString(),
						"outcome", snapshot.outcome())));
		return new RecommendationResult(decision.id(), decision.version() + 1, snapshot);
	}
}
