package vn.edu.finalism.logistics.modules.proof.application.port;

import java.time.Instant;

public interface OtpSenderPort {

	void send(String recipientEmail, String recipientName, String code, Instant expiresAt);
}
