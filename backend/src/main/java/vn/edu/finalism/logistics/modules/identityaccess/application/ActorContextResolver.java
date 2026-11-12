package vn.edu.finalism.logistics.modules.identityaccess.application;

import java.util.UUID;

import org.springframework.stereotype.Service;

import vn.edu.finalism.logistics.modules.identityaccess.application.port.UserAccountRepository;
import vn.edu.finalism.logistics.modules.identityaccess.application.port.UserAccountRepository.Authorities;
import vn.edu.finalism.logistics.modules.identityaccess.domain.UserAccount;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ErrorCode;

@Service
public class ActorContextResolver {

	private final UserAccountRepository users;

	ActorContextResolver(UserAccountRepository users) {
		this.users = users;
	}

	public ActorContext resolve(UUID userId, String correlationId) {
		return resolveWithAccount(userId, correlationId).context();
	}

	ResolvedActor resolveWithAccount(UUID userId, String correlationId) {
		UserAccount user = users.findById(userId)
				.filter(UserAccount::isActive)
				.orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED,
						"Please sign in again."));
		Authorities authorities = users.loadAuthorities(userId);
		ActorContext context = new ActorContext(userId, authorities.roles(), authorities.permissions(),
				authorities.scopeZoneIds(), correlationId);
		return new ResolvedActor(user, context);
	}

	record ResolvedActor(UserAccount user, ActorContext context) {
	}
}
