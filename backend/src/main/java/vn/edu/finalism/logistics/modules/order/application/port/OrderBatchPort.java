package vn.edu.finalism.logistics.modules.order.application.port;

import java.util.UUID;

import vn.edu.finalism.logistics.shared.ActorContext;

public interface OrderBatchPort {

	void detachForCancellation(ActorContext actor, UUID orderId);
}
