package vn.edu.finalism.logistics.modules.identityaccess.domain;

import java.time.Instant;
import java.util.UUID;

public record UserAccount(
		UUID id,
		String email,
		String displayName,
		String passwordHash,
		AccountStatus status,
		Instant lockedUntil,
		long version) {

	public boolean isLockedAt(Instant now) {
		return lockedUntil != null && lockedUntil.isAfter(now);
	}

	public boolean isActive() {
		return status == AccountStatus.ACTIVE;
	}
}
