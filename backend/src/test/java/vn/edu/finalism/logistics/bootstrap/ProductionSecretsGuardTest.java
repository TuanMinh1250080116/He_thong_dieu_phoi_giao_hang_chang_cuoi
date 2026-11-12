package vn.edu.finalism.logistics.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProductionSecretsGuardTest {

	private static final String STRONG = "a-long-random-secret-value-0123456789abcdef";

	@Test
	void developmentDefaultsAreRejected() {
		assertThat(ProductionSecretsGuard.problems("dev-only-secret-change-me-minimum-32-bytes-long",
				"dev-only-otp-pepper-change-me-0123456789", "dev-only-evidence-url-secret-change-me-0123", true))
				.containsExactly("JWT_SECRET", "OTP_PEPPER", "EVIDENCE_URL_SECRET");
	}

	@Test
	void shortOrBlankSecretsAndInsecureCookiesAreRejected() {
		assertThat(ProductionSecretsGuard.problems("short", " ", STRONG, false))
				.containsExactly("JWT_SECRET", "OTP_PEPPER", "app.security.refresh-cookie-secure");
	}

	@Test
	void strongSecretsPass() {
		assertThat(ProductionSecretsGuard.problems(STRONG, STRONG + "1", STRONG + "2", true)).isEmpty();
	}
}
