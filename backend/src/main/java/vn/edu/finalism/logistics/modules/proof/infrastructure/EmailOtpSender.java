package vn.edu.finalism.logistics.modules.proof.infrastructure;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import vn.edu.finalism.logistics.modules.proof.application.port.OtpSenderPort;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ErrorCode;

@Component
class EmailOtpSender implements OtpSenderPort {

	private final JavaMailSender mail;
	private final String from;

	EmailOtpSender(JavaMailSender mail, @Value("${app.proof.mail-from:noreply@logistics.local}") String from) {
		this.mail = mail;
		this.from = from;
	}

	@Override
	public void send(String recipientEmail, String recipientName, String code, Instant expiresAt) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(recipientEmail);
		message.setSubject("Mã xác nhận nhận hàng");
		message.setText("Xin chào " + recipientName + ",\n\nMã xác nhận nhận hàng của bạn là: " + code
				+ "\nMã có hiệu lực đến " + expiresAt + ". Chỉ đọc mã này cho tài xế khi bạn đã nhận hàng.");
		try {
			mail.send(message);
		}
		catch (MailException e) {
			throw new BusinessException(ErrorCode.EXTERNAL_DEPENDENCY_UNAVAILABLE,
					"The verification code could not be sent. Try again shortly.");
		}
	}
}
