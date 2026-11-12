package vn.edu.finalism.logistics.modules.dispatch.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import vn.edu.finalism.logistics.modules.dispatch.domain.Assignment;
import vn.edu.finalism.logistics.modules.dispatch.domain.DecisionStatus;
import vn.edu.finalism.logistics.modules.dispatch.domain.DispatchDecision;
import vn.edu.finalism.logistics.modules.dispatch.domain.Snapshot;

public interface DispatchRepository {

	Optional<DispatchDecision> findOpenDecisionByBatchForUpdate(UUID batchId);

	Optional<DispatchDecision> findDecisionByBatch(UUID batchId);

	Optional<AssignmentRow> findAssignmentRow(UUID assignmentId);

	Optional<DispatchDecision> findDecisionById(UUID id);

	Optional<DispatchDecision> findDecisionByIdForUpdate(UUID id);

	void insertDecision(DispatchDecision decision, Instant now);

	boolean updateDecision(UUID id, DecisionStatus status, long expectedVersion, Instant now);

	void closeActiveSnapshots(UUID decisionId, Instant now);

	void insertSnapshot(Snapshot snapshot, UUID generatedBy, int persistTopN);

	Optional<Snapshot> findSnapshot(UUID id);

	Optional<Snapshot> findLatestSnapshot(UUID decisionId);

	void markSnapshotConsumed(UUID id, Instant now);

	void insertAssignment(Assignment assignment, UUID recommendedDriverId, int sequenceNo);

	void insertAssignment(Assignment assignment, UUID recommendedDriverId, int sequenceNo, String overrideReason,
			String reassignReason);

	Optional<Assignment> findCurrentAssignmentForUpdate(UUID decisionId);

	int nextAssignmentSequence(UUID decisionId);

	boolean markAssignmentRejected(UUID id, String reasonCode, String description, Instant now);

	void supersedeAssignment(UUID id, UUID supersededBy);

	void cancelCurrentAssignment(UUID decisionId, String reason);

	Optional<Boolean> rejectionReasonRequiresDescription(String code);

	List<RejectionReason> activeRejectionReasons();

	void cancelDecision(UUID id, String reason, long expectedVersion, Instant now);

	void invalidateActiveSnapshots(UUID decisionId, String reason, Instant now);

	Optional<Assignment> findAssignment(UUID id);

	boolean markAssignmentAccepted(UUID id, Instant now);

	List<AssignmentRow> listAssignmentsForDriver(UUID driverId, int limit);

	void insertCommitment(UUID assignmentId, UUID driverId, Instant start, Instant end, Instant now);

	void releaseCommitments(UUID decisionId, Instant now);

	UUID batchIdOfAssignment(UUID assignmentId);

	Optional<AssignmentSummary> latestAssignmentSummary(UUID decisionId);

	record AssignmentSummary(UUID id, String status, String kind, UUID driverId, String driverName,
			String rejectionReasonCode) {
	}

	record RejectionReason(String code, boolean requiresDescription) {
	}

	record AssignmentRow(Assignment assignment, long decisionVersion, UUID deliveryId) {
	}
}
