package vn.edu.finalism.logistics.modules.identityaccess.application;

import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.identityaccess.application.ActorContextResolver.ResolvedActor;
import vn.edu.finalism.logistics.shared.ActorContext;

@Service
public class CurrentUserHandler {

	private final ActorContextResolver resolver;

	CurrentUserHandler(ActorContextResolver resolver) {
		this.resolver = resolver;
	}

	public record UserProfile(UUID id, String email, String displayName,
			Set<String> roles, Set<String> permissions, Set<UUID> scopeZoneIds) {
	}

	@Transactional(readOnly = true)
	public UserProfile handle(UUID userId, String correlationId) {
		ResolvedActor resolved = resolver.resolveWithAccount(userId, correlationId);
		ActorContext actor = resolved.context();
		return new UserProfile(userId, resolved.user().email(), resolved.user().displayName(),
				actor.roles(), actor.permissions(), actor.operationScopeIds());
	}
}
