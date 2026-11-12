package vn.edu.finalism.logistics.modules.proof.infrastructure;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import vn.edu.finalism.logistics.modules.proof.application.ProofSettings;

@Configuration
@EnableConfigurationProperties(ProofConfig.ProofProperties.class)
class ProofConfig {

	@ConfigurationProperties("app.proof")
	record ProofProperties(
			String otpPepper,
			String evidenceUrlSecret,
			@DefaultValue("10m") Duration otpTtl,
			@DefaultValue("5") int otpMaxAttempts,
			@DefaultValue("3") int otpIssueLimit,
			@DefaultValue("10m") Duration otpIssueWindow,
			@DefaultValue("120s") Duration evidenceUrlTtl) {
	}

	@Bean
	ProofSettings proofSettings(ProofProperties p) {
		if (p.otpPepper() == null || p.otpPepper().length() < 32 || p.evidenceUrlSecret() == null
				|| p.evidenceUrlSecret().length() < 32) {
			throw new IllegalStateException("app.proof.otp-pepper and app.proof.evidence-url-secret need at least 32 characters");
		}
		return new ProofSettings(p.otpPepper().getBytes(StandardCharsets.UTF_8),
				p.evidenceUrlSecret().getBytes(StandardCharsets.UTF_8), p.otpTtl(), p.otpMaxAttempts(),
				p.otpIssueLimit(), p.otpIssueWindow(), p.evidenceUrlTtl());
	}
}
