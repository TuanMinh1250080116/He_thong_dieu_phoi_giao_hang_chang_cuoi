package vn.edu.finalism.logistics.modules.dispatch.domain;

import java.time.Instant;
import java.util.UUID;

import vn.edu.finalism.logistics.shared.BusinessException;

public record Assignment(
		UUID id,
		UUID decisionId,
		UUID driverId,
		UUID vehicleId,
		String kind,
		AssignmentStatus status,
		UUID snapshotId,
		UUID decidedBy,
		Instant decidedAt) {

	public enum AssignmentStatus {
		PENDING, ACCEPTED, REJECTED, CANCELLED, SUPERSEDED
	}

	public Assignment reject() {
		if (status != AssignmentStatus.PENDING) {
			throw BusinessException.stateNotAllowed("The assignment is no longer waiting for a response.");
		}
		return new Assignment(id, decisionId, driverId, vehicleId, kind, AssignmentStatus.REJECTED, snapshotId,
				decidedBy, decidedAt);
	}

	public Assignment accept() {
		if (status != AssignmentStatus.PENDING) {
			throw BusinessException.stateNotAllowed("The assignment is no longer waiting for a response.");
		}
		return new Assignment(id, decisionId, driverId, vehicleId, kind, AssignmentStatus.ACCEPTED, snapshotId,
				decidedBy, decidedAt);
	}
}
