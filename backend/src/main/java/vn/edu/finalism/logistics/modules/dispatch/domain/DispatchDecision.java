package vn.edu.finalism.logistics.modules.dispatch.domain;

import java.util.UUID;

import vn.edu.finalism.logistics.shared.BusinessException;

public record DispatchDecision(UUID id, UUID batchId, DecisionStatus status, UUID createdBy, long version) {

	public static DispatchDecision open(UUID id, UUID batchId, UUID createdBy) {
		return new DispatchDecision(id, batchId, DecisionStatus.PENDING_DECISION, createdBy, 0);
	}

	public DispatchDecision awaitDriverResponse() {
		if (status != DecisionStatus.PENDING_DECISION) {
			throw BusinessException.stateNotAllowed("This batch already has a driver decision.");
		}
		return withStatus(DecisionStatus.AWAITING_DRIVER_RESPONSE);
	}

	public DispatchDecision confirm() {
		if (status != DecisionStatus.AWAITING_DRIVER_RESPONSE) {
			throw BusinessException.stateNotAllowed("The decision is not waiting for the driver.");
		}
		return withStatus(DecisionStatus.CONFIRMED);
	}

	public DispatchDecision rejectAssignment() {
		if (status != DecisionStatus.AWAITING_DRIVER_RESPONSE) {
			throw BusinessException.stateNotAllowed("The decision is not waiting for the driver.");
		}
		return withStatus(DecisionStatus.AWAITING_REASSIGNMENT);
	}

	public DispatchDecision reassign() {
		if (status != DecisionStatus.AWAITING_REASSIGNMENT && status != DecisionStatus.AWAITING_DRIVER_RESPONSE
				&& status != DecisionStatus.CONFIRMED) {
			throw BusinessException.stateNotAllowed("This decision has no driver to replace.");
		}
		return withStatus(DecisionStatus.AWAITING_DRIVER_RESPONSE);
	}

	public DispatchDecision cancel() {
		if (status == DecisionStatus.CANCELLED) {
			throw BusinessException.stateNotAllowed("The dispatch is already cancelled.");
		}
		return withStatus(DecisionStatus.CANCELLED);
	}

	private DispatchDecision withStatus(DecisionStatus next) {
		return new DispatchDecision(id, batchId, next, createdBy, version);
	}
}
