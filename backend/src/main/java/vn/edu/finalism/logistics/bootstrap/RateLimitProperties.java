package vn.edu.finalism.logistics.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.rate-limit")
record RateLimitProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("20") int loginPerMinute,
		@DefaultValue("10") int otpVerifyPerMinute,
		@DefaultValue("60") int uploadPerMinute,
		@DefaultValue("120") int evidenceViewPerMinute,
		@DefaultValue("1200") int userPerMinute) {
}
