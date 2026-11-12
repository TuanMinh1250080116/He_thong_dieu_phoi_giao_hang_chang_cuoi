package vn.edu.finalism.logistics.shared.event;

public interface EventPublicationPort {

	void append(EventEnvelope envelope);
}
