package vn.edu.finalism.logistics.bootstrap;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import vn.edu.finalism.logistics.modules.identityaccess.application.TokenSettings;
import vn.edu.finalism.logistics.platform.ratelimit.RateLimiter;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({SecurityProperties.class, RateLimitProperties.class})
class SecurityConfig {

	private static final String ISSUER = "logistics";

	@Bean
	SecurityFilterChain filterChain(HttpSecurity http, SecurityProblemHandlers problemHandlers, RateLimiter rateLimiter,
			RateLimitProperties rateLimitProperties) throws Exception {
		http
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
				.requestMatchers("/actuator/health/**").permitAll()
				.requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/evidence/view/*").permitAll()
				.anyRequest().authenticated())
			.oauth2ResourceServer(oauth -> oauth
				.jwt(Customizer.withDefaults())
				.authenticationEntryPoint(problemHandlers)
				.accessDeniedHandler(problemHandlers))
			.addFilterAfter(new RateLimitFilter(rateLimiter, rateLimitProperties), BearerTokenAuthenticationFilter.class)
			.exceptionHandling(ex -> ex
				.authenticationEntryPoint(problemHandlers)
				.accessDeniedHandler(problemHandlers));
		return http.build();
	}

	@Bean
	SecretKey jwtSecretKey(SecurityProperties properties) {
		byte[] bytes = properties.jwtSecret().getBytes(StandardCharsets.UTF_8);
		if (bytes.length < 32) {
			throw new IllegalStateException("app.security.jwt-secret must be at least 32 bytes for HS256");
		}
		return new SecretKeySpec(bytes, "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
		return decoder;
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	TokenSettings tokenSettings(SecurityProperties properties) {
		return new TokenSettings(properties.accessTokenTtl(), properties.refreshTokenTtl());
	}
}
