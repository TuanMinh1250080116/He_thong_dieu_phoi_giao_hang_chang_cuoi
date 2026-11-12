package vn.edu.finalism.logistics.modules.identityaccess.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.identityaccess.application.port.PasswordVerifier;
import vn.edu.finalism.logistics.modules.identityaccess.application.port.UserAccountRepository;
import vn.edu.finalism.logistics.modules.identityaccess.domain.LoginPolicy;
import vn.edu.finalism.logistics.modules.identityaccess.domain.UserAccount;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ClockPort;
import vn.edu.finalism.logistics.shared.ErrorCode;

@Service
public class LoginHandler {

	private final UserAccountRepository users;
	private final PasswordVerifier passwords;
	private final SessionIssuer sessionIssuer;
	private final ClockPort clock;

	LoginHandler(UserAccountRepository users, PasswordVerifier passwords,
			SessionIssuer sessionIssuer, ClockPort clock) {
		this.users = users;
		this.passwords = passwords;
		this.sessionIssuer = sessionIssuer;
		this.clock = clock;
	}

	public record LoginCommand(String identifier, String password, String userAgent) {
	}

	@Transactional(noRollbackFor = BusinessException.class)
	public AuthResult handle(LoginCommand command) {
		Instant now = clock.now();

		UserAccount user = users.findByLoginIdentifier(command.identifier().trim().toLowerCase()).orElse(null);
		if (user == null) {
			passwords.burnTime(command.password());
			throw authenticationFailed();
		}
		if (user.isLockedAt(now)) {
			long retryAfter = Math.max(1, Duration.between(now, user.lockedUntil()).toSeconds());
			throw new BusinessException(ErrorCode.ACCOUNT_LOCKED,
					"Too many failed attempts. Try again later.",
					Map.of("retryAfterSeconds", retryAfter));
		}
		if (!passwords.matches(command.password(), user.passwordHash())) {
			users.registerFailedLogin(user.id(), now,
					LoginPolicy.MAX_FAILED_ATTEMPTS, LoginPolicy.LOCK_DURATION);
			throw authenticationFailed();
		}
		if (!user.isActive()) {
			throw new BusinessException(ErrorCode.ACCOUNT_DISABLED, "This account is disabled.");
		}

		users.registerSuccessfulLogin(user.id(), now);
		return sessionIssuer.issue(user, now, command.userAgent());
	}

	private static BusinessException authenticationFailed() {
		return new BusinessException(ErrorCode.AUTHENTICATION_FAILED, "Invalid credentials.");
	}
}
