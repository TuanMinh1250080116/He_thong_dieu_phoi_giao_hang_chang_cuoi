package vn.edu.finalism.logistics.bootstrap;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import vn.edu.finalism.logistics.shared.CorrelationId;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

	private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {
		String id = request.getHeader(CorrelationId.HEADER);
		if (id == null || !VALID.matcher(id).matches()) {
			id = UUID.randomUUID().toString();
		}
		MDC.put(CorrelationId.MDC_KEY, id);
		response.setHeader(CorrelationId.HEADER, id);
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(CorrelationId.MDC_KEY);
		}
	}
}
