package vn.edu.finalism.logistics.modules.identityaccess.presentation;

import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.edu.finalism.logistics.modules.identityaccess.application.CurrentUserHandler;
import vn.edu.finalism.logistics.modules.identityaccess.application.CurrentUserHandler.UserProfile;
import vn.edu.finalism.logistics.shared.CorrelationId;

@RestController
class MeController {

	private final CurrentUserHandler handler;

	MeController(CurrentUserHandler handler) {
		this.handler = handler;
	}

	@GetMapping("/api/v1/me")
	ResponseEntity<UserProfile> me(@AuthenticationPrincipal Jwt jwt) {
		UserProfile profile = handler.handle(UUID.fromString(jwt.getSubject()), MDC.get(CorrelationId.MDC_KEY));
		return ResponseEntity.ok()
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.body(profile);
	}
}
