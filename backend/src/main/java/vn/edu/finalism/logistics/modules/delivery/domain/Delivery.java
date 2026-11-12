package vn.edu.finalism.logistics.modules.delivery.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import vn.edu.finalism.logistics.shared.BusinessException;

public record Delivery(
		UUID id,
		String code,
		UUID batchId,
		UUID decisionId,
		DeliveryStatus status,
		UUID currentAssignmentId,
		UUID assignedDriverId,
		UUID vehicleId,
		UUID podPolicyVersionId,
		Map<String, Object> podPolicySnapshot,
		Instant startedAt,
		Instant completedAt,
		List<DeliveryStop> stops,
		long version) {

	public static Delivery assigned(UUID id, String code, UUID batchId, UUID decisionId, UUID assignmentId,
			UUID driverId, UUID vehicleId, List<DeliveryStop> stops) {
		return new Delivery(id, code, batchId, decisionId, DeliveryStatus.ASSIGNED, assignmentId, driverId,
				vehicleId, null, null, null, null, List.copyOf(stops), 0);
	}

	public Delivery accept() {
		if (status != DeliveryStatus.ASSIGNED) {
			throw BusinessException.stateNotAllowed("Only an assigned delivery can be accepted.");
		}
		return with(DeliveryStatus.ACCEPTED, podPolicyVersionId, podPolicySnapshot, startedAt, completedAt, stops);
	}

	public Delivery awaitReassignment() {
		if (status != DeliveryStatus.ASSIGNED) {
			throw BusinessException.stateNotAllowed("Only an assigned delivery can wait for reassignment.");
		}
		return new Delivery(id, code, batchId, decisionId, DeliveryStatus.AWAITING_REASSIGNMENT, currentAssignmentId,
				assignedDriverId, vehicleId, podPolicyVersionId, podPolicySnapshot, startedAt, completedAt, stops,
				version);
	}

	public Delivery reassign(UUID assignmentId, UUID driverId, UUID newVehicleId) {
		if (status != DeliveryStatus.ASSIGNED && status != DeliveryStatus.ACCEPTED
				&& status != DeliveryStatus.AWAITING_REASSIGNMENT) {
			throw BusinessException.stateNotAllowed("A started or finished delivery cannot change driver.");
		}
		return new Delivery(id, code, batchId, decisionId, DeliveryStatus.ASSIGNED, assignmentId, driverId,
				newVehicleId, podPolicyVersionId, podPolicySnapshot, startedAt, completedAt, stops, version);
	}

	public Delivery cancel() {
		if (status == DeliveryStatus.IN_PROGRESS || status == DeliveryStatus.COMPLETED
				|| status == DeliveryStatus.CANCELLED) {
			throw BusinessException.stateNotAllowed("A started or finished delivery cannot be cancelled here.");
		}
		return with(DeliveryStatus.CANCELLED, podPolicyVersionId, podPolicySnapshot, startedAt, completedAt, stops);
	}

	public Delivery start(UUID policyVersionId, Map<String, Object> policySnapshot, Instant now) {
		if (status != DeliveryStatus.ACCEPTED) {
			throw BusinessException.stateNotAllowed("Only an accepted delivery can be started.");
		}
		return with(DeliveryStatus.IN_PROGRESS, policyVersionId, policySnapshot, now, completedAt, stops);
	}

	public record StopChange(Delivery delivery, DeliveryStop stop, StopStatus from) {
	}

	public StopChange performStopAction(UUID stopId, StopAction action, Instant now) {
		requireInProgress();
		DeliveryStop stop = stop(stopId);
		StopStatus from = stop.status();
		DeliveryStop updated;
		switch (action) {
			case START_STOP -> {
				if (stop.status() != StopStatus.PENDING) {
					throw BusinessException.stateNotAllowed("This stop has already been started.");
				}
				boolean earlierOpen = stops.stream().anyMatch(s -> s.sequenceNo() < stop.sequenceNo() && !s.isDone());
				if (earlierOpen) {
					throw BusinessException.stateNotAllowed("Finish the earlier stops first.");
				}
				updated = stop.withStatus(StopStatus.IN_PROGRESS);
			}
			case MARK_ARRIVED -> {
				if (stop.status() != StopStatus.IN_PROGRESS || stop.arrivedAt() != null) {
					throw BusinessException.stateNotAllowed("Start the stop before marking arrival.");
				}
				updated = stop.arrivedAt(now);
			}
			case MARK_READY_FOR_POD -> {
				if (stop.status() != StopStatus.IN_PROGRESS || stop.arrivedAt() == null) {
					throw BusinessException.stateNotAllowed("Mark arrival before preparing the proof of delivery.");
				}
				updated = stop;
			}
			case RESUME_AFTER_EXCEPTION -> {
					if (stop.status() != StopStatus.BLOCKED) {
						throw BusinessException.stateNotAllowed("This stop is not blocked.");
					}
					updated = stop.withStatus(StopStatus.IN_PROGRESS);
				}
				default -> throw BusinessException.stateNotAllowed("This action is not available yet.");
		}
		return new StopChange(replace(updated), updated, from);
	}

	public StopChange blockStop(UUID stopId) {
		requireInProgress();
		DeliveryStop stop = stop(stopId);
		if (stop.status() != StopStatus.IN_PROGRESS) {
			throw BusinessException.stateNotAllowed("Start the stop before reporting a problem that blocks it.");
		}
		DeliveryStop blocked = stop.withStatus(StopStatus.BLOCKED);
		return new StopChange(replace(blocked), blocked, stop.status());
	}

	public StopChange completeStop(UUID stopId, Instant now) {
		requireInProgress();
		DeliveryStop stop = stop(stopId);
		if (stop.status() != StopStatus.IN_PROGRESS) {
			throw BusinessException.stateNotAllowed("The stop must be in progress to receive a proof of delivery.");
		}
		DeliveryStop completed = stop.completedAt(now);
		return new StopChange(replace(completed), completed, stop.status());
	}

	public CompletionCheck completionCheck(Set<UUID> stopIdsWithPod, Collection<UUID> blockingExceptionIds) {
		List<UUID> incomplete = new ArrayList<>();
		List<UUID> missingPod = new ArrayList<>();
		for (DeliveryStop stop : stops) {
			if (stop.status() != StopStatus.COMPLETED) {
				incomplete.add(stop.id());
			}
			else if (!stopIdsWithPod.contains(stop.id())) {
				missingPod.add(stop.id());
			}
		}
		return new CompletionCheck(incomplete, missingPod, List.copyOf(blockingExceptionIds));
	}

	public Delivery complete(Instant now, CompletionCheck check) {
		requireInProgress();
		if (!check.allowed()) {
			throw BusinessException.stateNotAllowed("The delivery still has open prerequisites.");
		}
		return with(DeliveryStatus.COMPLETED, podPolicyVersionId, podPolicySnapshot, startedAt, now, stops);
	}

	public DeliveryStop stop(UUID stopId) {
		return stops.stream().filter(s -> s.id().equals(stopId)).findFirst()
				.orElseThrow(() -> BusinessException.notFound("Stop"));
	}

	public int completedStops() {
		return (int) stops.stream().filter(s -> s.status() == StopStatus.COMPLETED).count();
	}

	private void requireInProgress() {
		if (status != DeliveryStatus.IN_PROGRESS) {
			throw BusinessException.stateNotAllowed("The delivery is not in progress.");
		}
	}

	private Delivery replace(DeliveryStop updated) {
		List<DeliveryStop> next = stops.stream().map(s -> s.id().equals(updated.id()) ? updated : s).toList();
		return with(status, podPolicyVersionId, podPolicySnapshot, startedAt, completedAt, next);
	}

	private Delivery with(DeliveryStatus nextStatus, UUID policyVersionId, Map<String, Object> policySnapshot,
			Instant started, Instant completed, List<DeliveryStop> nextStops) {
		return new Delivery(id, code, batchId, decisionId, nextStatus, currentAssignmentId, assignedDriverId,
				vehicleId, policyVersionId, policySnapshot, started, completed, nextStops, version);
	}
}
