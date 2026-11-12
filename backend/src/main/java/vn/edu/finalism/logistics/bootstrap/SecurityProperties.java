package vn.edu.finalism.logistics.bootstrap;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.security")
record SecurityProperties(
		String jwtSecret,
		@DefaultValue("15m") Duration accessTokenTtl,
		@DefaultValue("7d") Duration refreshTokenTtl) {
}
