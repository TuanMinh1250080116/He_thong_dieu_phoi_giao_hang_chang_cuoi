package vn.edu.finalism.logistics.modules.notification.application.port;

public interface EmailSenderPort {

	void send(String to, String subject, String body) throws EmailDeliveryException;

	class EmailDeliveryException extends Exception {

		private final String safeCode;

		public EmailDeliveryException(String safeCode) {
			super(safeCode);
			this.safeCode = safeCode;
		}

		public String safeCode() {
			return safeCode;
		}
	}
}
