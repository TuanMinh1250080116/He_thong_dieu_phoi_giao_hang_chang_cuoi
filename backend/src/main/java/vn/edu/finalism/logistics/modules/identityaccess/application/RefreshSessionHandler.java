package vn.edu.finalism.logistics.modules.identityaccess.application;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.identityaccess.application.port.RefreshSessionRepository;
import vn.edu.finalism.logistics.modules.identityaccess.application.port.RefreshTokenCodec;
import vn.edu.finalism.logistics.modules.identityaccess.application.port.UserAccountRepository;
import vn.edu.finalism.logistics.modules.identityaccess.domain.RefreshSession;
import vn.edu.finalism.logistics.modules.identityaccess.domain.UserAccount;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.ErrorCode;

@Service
public class RefreshSessionHandler {

	private final UserAccountRepository users;
	private final RefreshSessionRepository sessions;
	private final RefreshTokenCodec refreshCodec;
	private final SessionIssuer sessionIssuer;
	private final ClockPort clock;

	RefreshSessionHandler(UserAccountRepository users, RefreshSessionRepository sessions,
			RefreshTokenCodec refreshCodec, SessionIssuer sessionIssuer, ClockPort clock) {
		this.users = users;
		this.sessions = sessions;
		this.refreshCodec = refreshCodec;
		this.sessionIssuer = sessionIssuer;
		this.clock = clock;
	}

	public record RefreshCommand(String refreshToken, String userAgent) {
	}

	@Transactional(noRollbackFor = BusinessException.class)
	public AuthResult handle(RefreshCommand command) {
		Instant now = clock.now();
		if (command.refreshToken() == null || command.refreshToken().isBlank()) {
			throw authenticationRequired();
		}

		RefreshSession session = sessions.findByTokenHash(refreshCodec.hash(command.refreshToken()))
				.orElseThrow(RefreshSessionHandler::authenticationRequired);

		if (session.isRevoked()) {
			sessions.revokeAllForUser(session.userId(), now);
			throw authenticationRequired();
		}
		if (session.isExpiredAt(now)) {
			throw authenticationRequired();
		}
		if (!sessions.revoke(session.id(), now)) {
			sessions.revokeAllForUser(session.userId(), now);
			throw authenticationRequired();
		}

		UserAccount user = users.findById(session.userId()).orElse(null);
		if (user == null || !user.isActive()) {
			sessions.revokeAllForUser(session.userId(), now);
			throw authenticationRequired();
		}
		return sessionIssuer.issue(user, now, command.userAgent());
	}

	private static BusinessException authenticationRequired() {
		return new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED, "Please sign in again.");
	}
}
