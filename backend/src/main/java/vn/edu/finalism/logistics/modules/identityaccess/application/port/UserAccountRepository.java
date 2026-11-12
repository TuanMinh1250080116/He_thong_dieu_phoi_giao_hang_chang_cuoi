package vn.edu.finalism.logistics.modules.identityaccess.application.port;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import vn.edu.finalism.logistics.modules.identityaccess.domain.UserAccount;

public interface UserAccountRepository {

	Optional<UserAccount> findByLoginIdentifier(String identifier);

	Optional<UserAccount> findById(UUID id);

	Authorities loadAuthorities(UUID userId);

	void registerFailedLogin(UUID userId, Instant now, int maxAttempts, Duration lockDuration);

	void registerSuccessfulLogin(UUID userId, Instant now);

	record Authorities(Set<String> roles, Set<String> permissions, Set<UUID> scopeZoneIds) {
	}
}
