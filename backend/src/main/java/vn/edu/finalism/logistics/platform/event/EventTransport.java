package vn.edu.finalism.logistics.platform.event;

public interface EventTransport {

	void send(OutboundEvent event);
}
