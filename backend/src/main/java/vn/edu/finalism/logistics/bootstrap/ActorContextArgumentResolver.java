package vn.edu.finalism.logistics.bootstrap;

import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import vn.edu.finalism.logistics.modules.identityaccess.application.ActorContextResolver;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.CorrelationId;
import vn.edu.finalism.logistics.shared.ErrorCode;

@Component
class ActorContextArgumentResolver implements HandlerMethodArgumentResolver {

	private final ActorContextResolver resolver;

	ActorContextArgumentResolver(ActorContextResolver resolver) {
		this.resolver = resolver;
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return ActorContext.class.equals(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
			throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED, "Please sign in.");
		}
		return resolver.resolve(UUID.fromString(jwt.getSubject()), MDC.get(CorrelationId.MDC_KEY));
	}
}
