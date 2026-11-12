package vn.edu.finalism.logistics.modules.dispatch.application;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.finalism.logistics.modules.dispatch.application.port.BatchRepository;
import vn.edu.finalism.logistics.modules.dispatch.domain.DeliveryBatch;
import vn.edu.finalism.logistics.shared.ActorContext;
import vn.edu.finalism.logistics.shared.BusinessException;

@Service
public class BatchQueryHandler {

	private final BatchRepository batches;

	BatchQueryHandler(BatchRepository batches) {
		this.batches = batches;
	}

	@Transactional(readOnly = true)
	public DeliveryBatch get(ActorContext actor, UUID batchId) {
		actor.requirePermission("dispatch.board.view");
		DeliveryBatch batch = batches.findById(batchId).orElseThrow(() -> BusinessException.notFound("Batch"));
		actor.requireScope(batch.zoneId());
		return batch;
	}
}
