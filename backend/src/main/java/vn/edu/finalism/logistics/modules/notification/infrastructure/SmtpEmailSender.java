package vn.edu.finalism.logistics.modules.notification.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import vn.edu.finalism.logistics.modules.notification.application.port.EmailSenderPort;

@Component
class SmtpEmailSender implements EmailSenderPort {

	private final JavaMailSender mail;
	private final String from;

	SmtpEmailSender(JavaMailSender mail, @Value("${app.notifications.email.from:noreply@logistics.local}") String from) {
		this.mail = mail;
		this.from = from;
	}

	@Override
	public void send(String to, String subject, String body) throws EmailDeliveryException {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(to);
		message.setSubject(subject);
		message.setText(body);
		try {
			mail.send(message);
		}
		catch (MailParseException e) {
			throw new EmailDeliveryException("RECIPIENT_REJECTED");
		}
		catch (MailAuthenticationException e) {
			throw new EmailDeliveryException("SMTP_AUTH_FAILED");
		}
		catch (MailException e) {
			throw new EmailDeliveryException("SMTP_UNAVAILABLE");
		}
	}
}
