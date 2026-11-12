package vn.edu.finalism.logistics.bootstrap;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
@Profile("prod")
class ProductionSecretsGuard {

	private final String jwtSecret;
	private final String otpPepper;
	private final String evidenceUrlSecret;
	private final boolean refreshCookieSecure;

	ProductionSecretsGuard(@Value("${app.security.jwt-secret}") String jwtSecret,
			@Value("${app.proof.otp-pepper}") String otpPepper,
			@Value("${app.proof.evidence-url-secret}") String evidenceUrlSecret,
			@Value("${app.security.refresh-cookie-secure:true}") boolean refreshCookieSecure) {
		this.jwtSecret = jwtSecret;
		this.otpPepper = otpPepper;
		this.evidenceUrlSecret = evidenceUrlSecret;
		this.refreshCookieSecure = refreshCookieSecure;
	}

	@PostConstruct
	void verify() {
		List<String> problems = problems(jwtSecret, otpPepper, evidenceUrlSecret, refreshCookieSecure);
		if (!problems.isEmpty()) {
			throw new IllegalStateException("Refusing to start with unsafe production settings: " + problems);
		}
	}

	static List<String> problems(String jwtSecret, String otpPepper, String evidenceUrlSecret,
			boolean refreshCookieSecure) {
		List<String> problems = new ArrayList<>();
		if (isDefault(jwtSecret)) {
			problems.add("JWT_SECRET");
		}
		if (isDefault(otpPepper)) {
			problems.add("OTP_PEPPER");
		}
		if (isDefault(evidenceUrlSecret)) {
			problems.add("EVIDENCE_URL_SECRET");
		}
		if (!refreshCookieSecure) {
			problems.add("app.security.refresh-cookie-secure");
		}
		return problems;
	}

	private static boolean isDefault(String value) {
		return value == null || value.isBlank() || value.startsWith("dev-only") || value.length() < 32;
	}
}
