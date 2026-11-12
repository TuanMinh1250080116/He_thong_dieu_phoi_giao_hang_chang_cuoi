package vn.edu.finalism.logistics.modules.identityaccess.presentation;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import vn.edu.finalism.logistics.modules.identityaccess.application.AuthResult;
import vn.edu.finalism.logistics.modules.identityaccess.application.LoginHandler;
import vn.edu.finalism.logistics.modules.identityaccess.application.LoginHandler.LoginCommand;
import vn.edu.finalism.logistics.modules.identityaccess.application.LogoutHandler;
import vn.edu.finalism.logistics.modules.identityaccess.application.RefreshSessionHandler;
import vn.edu.finalism.logistics.modules.identityaccess.application.RefreshSessionHandler.RefreshCommand;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ErrorCode;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

	static final String REFRESH_COOKIE = "refresh_token";
	private static final String COOKIE_PATH = "/api/v1/auth";

	private final LoginHandler loginHandler;
	private final RefreshSessionHandler refreshHandler;
	private final LogoutHandler logoutHandler;
	private final boolean cookieSecure;

	AuthController(LoginHandler loginHandler, RefreshSessionHandler refreshHandler, LogoutHandler logoutHandler,
			@Value("${app.security.refresh-cookie-secure:true}") boolean cookieSecure) {
		this.loginHandler = loginHandler;
		this.refreshHandler = refreshHandler;
		this.logoutHandler = logoutHandler;
		this.cookieSecure = cookieSecure;
	}

	record LoginRequest(@NotBlank String identifier, @NotBlank String password) {
	}

	record UserDto(UUID id, String email, String displayName, Set<String> roles) {
	}

	record AuthResponse(String accessToken, long expiresIn, UserDto user) {
	}

	@PostMapping("/login")
	ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
			@RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
		AuthResult result = loginHandler.handle(new LoginCommand(request.identifier(), request.password(), userAgent));
		return respond(result);
	}

	@PostMapping("/refresh")
	ResponseEntity<AuthResponse> refresh(
			@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
			@RequestHeader(value = "X-Requested-With", required = false) String requestedWith,
			@RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
		if (!"fetch".equals(requestedWith)) {
			throw new BusinessException(ErrorCode.FORBIDDEN, "Missing X-Requested-With header.");
		}
		return respond(refreshHandler.handle(new RefreshCommand(refreshToken, userAgent)));
	}

	@PostMapping("/logout")
	ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
		logoutHandler.handle(refreshToken);
		return ResponseEntity.noContent()
				.header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.build();
	}

	private ResponseEntity<AuthResponse> respond(AuthResult result) {
		AuthResult.IssuedTokens tokens = result.tokens();
		AuthResult.UserSummary user = result.user();
		AuthResponse body = new AuthResponse(tokens.accessToken(), tokens.expiresInSeconds(),
				new UserDto(user.id(), user.email(), user.displayName(), user.roles()));
		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE,
						refreshCookie(tokens.refreshToken(), Duration.ofSeconds(tokens.refreshExpiresInSeconds())).toString())
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.body(body);
	}

	private ResponseCookie refreshCookie(String value, Duration maxAge) {
		return ResponseCookie.from(REFRESH_COOKIE, value)
				.httpOnly(true)
				.secure(cookieSecure)
				.sameSite("Strict")
				.path(COOKIE_PATH)
				.maxAge(maxAge)
				.build();
	}
}
