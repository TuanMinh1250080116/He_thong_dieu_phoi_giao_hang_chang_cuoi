package vn.edu.finalism.logistics.modules.identityaccess.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.identityaccess.application.port.RefreshSessionRepository;
import vn.edu.finalism.logistics.modules.identityaccess.application.port.RefreshTokenCodec;
import vn.edu.finalism.logistics.shared.ClockPort;

@Service
public class LogoutHandler {

	private final RefreshSessionRepository sessions;
	private final RefreshTokenCodec refreshCodec;
	private final ClockPort clock;

	LogoutHandler(RefreshSessionRepository sessions, RefreshTokenCodec refreshCodec, ClockPort clock) {
		this.sessions = sessions;
		this.refreshCodec = refreshCodec;
		this.clock = clock;
	}

	@Transactional
	public void handle(String refreshToken) {
		if (refreshToken == null || refreshToken.isBlank()) {
			return;
		}
		sessions.findByTokenHash(refreshCodec.hash(refreshToken))
				.ifPresent(session -> sessions.revoke(session.id(), clock.now()));
	}
}
