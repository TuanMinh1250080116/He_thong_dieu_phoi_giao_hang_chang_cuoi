package vn.edu.finalism.logistics.modules.proof.application;

import java.time.Duration;

public record ProofSettings(
		byte[] otpPepper,
		byte[] evidenceUrlSecret,
		Duration otpTtl,
		int otpMaxAttempts,
		int otpIssueLimit,
		Duration otpIssueWindow,
		Duration evidenceUrlTtl) {
}
