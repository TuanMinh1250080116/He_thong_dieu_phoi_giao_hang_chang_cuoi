package vn.edu.finalism.logistics.modules.identityaccess.application;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.edu.finalism.logistics.modules.identityaccess.application.AuthResult.IssuedTokens;
import vn.edu.finalism.logistics.modules.identityaccess.application.AuthResult.UserSummary;
import vn.edu.finalism.logistics.modules.identityaccess.application.port.AccessTokenIssuer;
import vn.edu.finalism.logistics.modules.identityaccess.application.port.AccessTokenIssuer.AccessToken;
import vn.edu.finalism.logistics.modules.identityaccess.application.port.RefreshSessionRepository;
import vn.edu.finalism.logistics.modules.identityaccess.application.port.RefreshTokenCodec;
import vn.edu.finalism.logistics.modules.identityaccess.application.port.UserAccountRepository;
import vn.edu.finalism.logistics.modules.identityaccess.application.port.UserAccountRepository.Authorities;
import vn.edu.finalism.logistics.modules.identityaccess.domain.RefreshSession;
import vn.edu.finalism.logistics.modules.identityaccess.domain.UserAccount;

@Component
class SessionIssuer {

	private final UserAccountRepository users;
	private final RefreshSessionRepository sessions;
	private final AccessTokenIssuer accessTokens;
	private final RefreshTokenCodec refreshCodec;
	private final TokenSettings settings;

	SessionIssuer(UserAccountRepository users, RefreshSessionRepository sessions,
			AccessTokenIssuer accessTokens, RefreshTokenCodec refreshCodec, TokenSettings settings) {
		this.users = users;
		this.sessions = sessions;
		this.accessTokens = accessTokens;
		this.refreshCodec = refreshCodec;
		this.settings = settings;
	}

	AuthResult issue(UserAccount user, Instant now, String userAgent) {
		Authorities authorities = users.loadAuthorities(user.id());
		AccessToken access = accessTokens.issue(user.id(), authorities.roles(), now);

		String rawRefresh = refreshCodec.generate();
		sessions.insert(new RefreshSession(
				UUID.randomUUID(),
				user.id(),
				refreshCodec.hash(rawRefresh),
				now,
				now.plus(settings.refreshTtl()),
				null,
				userAgent == null ? null : refreshCodec.hash(userAgent)));

		return new AuthResult(
				new IssuedTokens(access.value(), access.expiresInSeconds(),
						rawRefresh, settings.refreshTtl().toSeconds()),
				new UserSummary(user.id(), user.email(), user.displayName(), authorities.roles()));
	}
}
