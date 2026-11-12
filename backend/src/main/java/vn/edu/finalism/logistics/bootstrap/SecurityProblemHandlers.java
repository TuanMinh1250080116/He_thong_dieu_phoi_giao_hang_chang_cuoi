package vn.edu.finalism.logistics.bootstrap;

import java.io.IOException;

import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import vn.edu.finalism.logistics.shared.CorrelationId;
import vn.edu.finalism.logistics.shared.ErrorCode;

@Component
class SecurityProblemHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		write(response, 401, "Unauthorized", ErrorCode.AUTHENTICATION_REQUIRED);
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException exception) throws IOException {
		write(response, 403, "Forbidden", ErrorCode.FORBIDDEN);
	}

	private static void write(HttpServletResponse response, int status, String title, ErrorCode code)
			throws IOException {
		response.setStatus(status);
		response.setContentType("application/problem+json");
		response.setCharacterEncoding("UTF-8");
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		String correlationId = MDC.get(CorrelationId.MDC_KEY);
		response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status
				+ ",\"code\":\"" + code.name() + "\",\"correlationId\":\"" + correlationId
				+ "\",\"retryable\":" + code.retryable() + "}");
	}
}
