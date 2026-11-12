package vn.edu.finalism.logistics.bootstrap;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import vn.edu.finalism.logistics.platform.ratelimit.RateLimiter;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.CorrelationId;
import vn.edu.finalism.logistics.shared.ErrorCode;

class RateLimitFilter extends OncePerRequestFilter {

	private static final Duration MINUTE = Duration.ofMinutes(1);

	private final RateLimiter limiter;
	private final RateLimitProperties properties;

	RateLimitFilter(RateLimiter limiter, RateLimitProperties properties) {
		this.limiter = limiter;
		this.properties = properties;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !properties.enabled() || !request.getRequestURI().startsWith("/api/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		try {
			enforce(request);
		}
		catch (BusinessException e) {
			reject(response, e);
			return;
		}
		chain.doFilter(request, response);
	}

	private void enforce(HttpServletRequest request) {
		String path = request.getRequestURI();
		String method = request.getMethod();
		String ip = request.getRemoteAddr();
		if (path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/refresh")) {
			limiter.check("login:" + ip, properties.loginPerMinute(), MINUTE);
			return;
		}
		if ("GET".equals(method) && path.startsWith("/api/v1/evidence/view/")) {
			limiter.check("evidence-view:" + ip, properties.evidenceViewPerMinute(), MINUTE);
			return;
		}
		String user = subject();
		if (user == null) {
			return;
		}
		if (path.startsWith("/api/v1/otp-challenges/") && path.endsWith("/verify")) {
			limiter.check("otp-verify:" + user, properties.otpVerifyPerMinute(), MINUTE);
		}
		else if (path.contains("/evidence-uploads") || (path.startsWith("/api/v1/evidence/") && !"GET".equals(method))) {
			limiter.check("upload:" + user, properties.uploadPerMinute(), MINUTE);
		}
		limiter.check("user:" + user, properties.userPerMinute(), MINUTE);
	}

	private static String subject() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return authentication instanceof JwtAuthenticationToken token ? token.getName() : null;
	}

	private static void reject(HttpServletResponse response, BusinessException e) throws IOException {
		Object retry = e.details() == null ? null : e.details().get("retryAfterSeconds");
		response.setStatus(429);
		response.setContentType("application/problem+json");
		response.setCharacterEncoding("UTF-8");
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		if (retry != null) {
			response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retry));
		}
		String correlationId = MDC.get(CorrelationId.MDC_KEY);
		response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,\"code\":\""
				+ ErrorCode.RATE_LIMITED.name() + "\",\"correlationId\":\"" + correlationId
				+ "\",\"retryable\":true,\"details\":{\"retryAfterSeconds\":" + (retry == null ? 1 : retry) + "}}");
	}
}
