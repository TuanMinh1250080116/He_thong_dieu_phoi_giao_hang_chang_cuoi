package vn.edu.finalism.logistics.modules.driverfleet.domain;

import java.util.Map;
import java.util.UUID;

import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ErrorCode;

public record Driver(
		UUID id,
		UUID userId,
		String code,
		String fullName,
		boolean active,
		Availability availability,
		UUID vehicleId,
		long version) {

	public Driver reserve() {
		if (!active || availability != Availability.AVAILABLE) {
			throw new BusinessException(ErrorCode.DRIVER_INELIGIBLE, "The driver is not available.",
					Map.of("reasons", java.util.List.of("NOT_AVAILABLE")));
		}
		return withAvailability(Availability.RESERVED);
	}

	public Driver markBusy() {
		if (availability != Availability.RESERVED) {
			throw BusinessException.stateNotAllowed("Only a reserved driver can start a delivery.");
		}
		return withAvailability(Availability.BUSY);
	}

	public boolean hasSystemObligation() {
		return availability == Availability.RESERVED || availability == Availability.BUSY;
	}

	public Driver goOnline(boolean hasOpenObligation) {
		if (!active) {
			throw BusinessException.stateNotAllowed("The driver account is inactive.");
		}
		if (hasSystemObligation() || hasOpenObligation) {
			throw BusinessException.stateNotAllowed(
					"You still have an assignment or a delivery in progress, so you cannot change availability.");
		}
		return withAvailability(Availability.AVAILABLE);
	}

	public Driver goOffline() {
		if (hasSystemObligation()) {
			throw BusinessException.stateNotAllowed(
					"You still have an assignment or a delivery in progress, so you cannot go offline.");
		}
		return withAvailability(Availability.OFFLINE);
	}

	public Driver deactivate() {
		if (hasSystemObligation()) {
			throw BusinessException.stateNotAllowed("The driver still has an assignment or a delivery in progress.");
		}
		return new Driver(id, userId, code, fullName, false, Availability.OFFLINE, vehicleId, version);
	}

	public Driver activate() {
		return new Driver(id, userId, code, fullName, true, availability, vehicleId, version);
	}

	public Driver release() {
		if (availability != Availability.RESERVED && availability != Availability.BUSY) {
			throw BusinessException.stateNotAllowed("The driver has nothing to release.");
		}
		return withAvailability(Availability.AVAILABLE);
	}

	private Driver withAvailability(Availability next) {
		return new Driver(id, userId, code, fullName, active, next, vehicleId, version);
	}
}
